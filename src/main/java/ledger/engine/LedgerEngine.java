package ledger.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import ledger.input.Account;
import ledger.input.EventKind;
import ledger.input.SourceEvent;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import ledger.record.AuthorizationFact;
import ledger.record.DayClose;
import ledger.record.ErrorCode;
import ledger.record.FeePolicyError;
import ledger.record.LedgerRecord;
import ledger.record.Outcome;
import ledger.record.Posting;
import ledger.record.PostingType;

/**
 * The single-writer, in-memory ledger. Feed it {@link SourceEvent}s in input order with {@link #submit}.
 *
 * <p>Everything the engine decides is appended to one list of {@link LedgerRecord}s and never
 * changed or removed. Balances are computed from that list by {@link LedgerView}.
 *
 * <p>The clock: each event has a booked day. The engine keeps a "watermark" — the latest booked day
 * seen — that only moves forward. When an event with a later booked day arrives, every earlier unclosed
 * day is closed first (fee assessed, original snapshot frozen). A late event (booked earlier than the
 * watermark) does not move it back. Day 6 is closed only by {@link #finish}.
 *
 * <p>Backdated money: when a posting's value day is an already-closed day, those closed days are
 * re-checked in order, oldest first, so a fee on Day 2 lowers Day 3 and Day 4 before they are checked.
 * One fee key per account and day prevents a second fee for the same day.
 *
 * <p>Not thread-safe by design: one ordered writer only.
 */
public final class LedgerEngine {

    public static final int FIRST_DAY = 1;
    public static final int LAST_DAY = 6;

    private final Map<String, Account> accounts;
    private final Map<CurrencyCode, Money> feeTariffs;

    private final List<LedgerRecord> records = new ArrayList<>();
    private final List<SourceEvent> inputLog = new ArrayList<>();
    private final Map<String, SourceEvent> sourcesById = new HashMap<>();
    private final Map<String, Outcome> outcomesById = new HashMap<>();
    private final Set<String> feeKeys = new HashSet<>();
    private final Set<String> missingFeePolicyKeys = new HashSet<>();
    private final Set<String> reversedSourceIds = new HashSet<>();
    private final Map<String, Long> checkpoints = new LinkedHashMap<>();

    private int watermark = FIRST_DAY;
    private int lastClosedDay = FIRST_DAY - 1;
    private boolean finalized;

    /**
     * @param accounts   accounts with their opening balances
     * @param feeTariffs overdraft fee per currency; a currency without an entry gives MISSING_FEE_POLICY
     */
    public LedgerEngine(List<Account> accounts, Map<CurrencyCode, Money> feeTariffs) {
        Map<String, Account> byId = new TreeMap<>();
        for (Account a : accounts) {
            if (byId.put(a.id(), a) != null) {
                throw new IllegalArgumentException("Duplicate account " + a.id());
            }
        }
        this.accounts = Collections.unmodifiableMap(byId);
        feeTariffs.forEach((ccy, fee) -> {
            if (fee.currency() != ccy || fee.signum() <= 0) {
                throw new IllegalArgumentException("Invalid tariff " + fee + " for " + ccy);
            }
        });
        this.feeTariffs = Map.copyOf(feeTariffs);
    }

    /** The brief's tariff: AED 25.00 only. No BHD tariff is invented. */
    public static Map<CurrencyCode, Money> standardFeeTariffs() {
        return Map.of(CurrencyCode.AED, Money.of(CurrencyCode.AED, "25.00"));
    }

    // ------------------------------------------------------------------ public API

    /**
     * Processes one source event. Business problems are returned as a REJECTED outcome (and recorded);
     * they never throw. An identical re-submission of a known source id returns the original outcome
     * and changes nothing.
     */
    public Outcome submit(SourceEvent event) {
        Objects.requireNonNull(event, "event");
        inputLog.add(event);
        int inputPosition = inputLog.size();

        if (event.sourceId().isBlank()) {
            // Not registered for idempotency and the clock does not move: the event cannot be identified.
            return appendOutcome(event, inputPosition, Outcome.Status.REJECTED, ErrorCode.MISSING_SOURCE_ID,
                    "Source id is required", List.of());
        }
        SourceEvent previous = sourcesById.get(event.sourceId());
        if (previous != null) {
            if (previous.equals(event)) {
                return outcomesById.get(event.sourceId());
            }
            return appendOutcome(event, inputPosition, Outcome.Status.REJECTED, ErrorCode.DUPLICATE_SOURCE_CONFLICT,
                    "Source id " + event.sourceId() + " was already used for a different event", List.of());
        }
        sourcesById.put(event.sourceId(), event);

        Outcome outcome;
        if (finalized) {
            outcome = appendOutcome(event, inputPosition, Outcome.Status.REJECTED, ErrorCode.WINDOW_FINALIZED,
                    "Window already finalized", List.of());
        } else if (event.bookedDay() < FIRST_DAY || event.bookedDay() > LAST_DAY) {
            outcome = appendOutcome(event, inputPosition, Outcome.Status.REJECTED, ErrorCode.INVALID_BOOKED_DAY,
                    "Booked day " + event.bookedDay() + " is outside Day1..Day6", List.of());
        } else {
            advanceClockTo(event.bookedDay());
            checkpoints.putIfAbsent("before:" + event.sourceId(), lastSequence());
            int sizeBefore = records.size();
            Applied applied;
            try {
                applied = apply(event);
            } catch (DomainException e) {
                if (records.size() != sizeBefore) {
                    // Validation must finish before the first write; anything else is a programming error.
                    throw new IllegalStateException("Partial effects left by rejected " + event.sourceId(), e);
                }
                applied = new Applied(Outcome.Status.REJECTED, e.code(), e.getMessage(), List.of());
            }
            outcome = appendOutcome(event, inputPosition, applied.status, applied.code, applied.message,
                    applied.effects);
        }
        outcomesById.put(event.sourceId(), outcome);
        checkpoints.putIfAbsent("after:" + event.sourceId(), lastSequence());
        return outcome;
    }

    /** End of input: closes every remaining day including Day 6. Calling it again does nothing. */
    public void finish() {
        if (finalized) {
            return;
        }
        for (int d = lastClosedDay + 1; d <= LAST_DAY; d++) {
            closeDay(d);
        }
        watermark = LAST_DAY;
        finalized = true;
    }

    /** Immutable copy of the whole append-only log. */
    public List<LedgerRecord> records() {
        return List.copyOf(records);
    }

    /** Events in the order they were submitted, including duplicates and rejected ones. */
    public List<SourceEvent> inputLog() {
        return List.copyOf(inputLog);
    }

    /** A read-only projection over a copy of the current log. */
    public LedgerView view() {
        return new LedgerView(accounts, records);
    }

    public List<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    /** The first outcome recorded for this source id. */
    public Optional<Outcome> outcome(String sourceId) {
        return Optional.ofNullable(outcomesById.get(sourceId));
    }

    public long lastSequence() {
        return records.size();
    }

    public int watermark() {
        return watermark;
    }

    public int lastClosedDay() {
        return lastClosedDay;
    }

    public boolean isFinalized() {
        return finalized;
    }

    /**
     * Named knowledge cutoffs (record sequences): "before:E7", "principal:E7" (after the posting, before
     * any re-check fee), "after:E7", "close:Day5", ...
     */
    public long checkpoint(String name) {
        Long seq = checkpoints.get(name);
        if (seq == null) {
            throw new IllegalArgumentException("No checkpoint " + name);
        }
        return seq;
    }

    public Map<String, Long> checkpoints() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(checkpoints));
    }

    // ------------------------------------------------------------------ clock and closes

    private void advanceClockTo(int bookedDay) {
        if (bookedDay <= watermark) {
            return; // late or same-day event: the watermark never moves backwards
        }
        for (int d = lastClosedDay + 1; d < bookedDay; d++) {
            closeDay(d);
        }
        watermark = bookedDay;
    }

    /** Assesses each account's fee for the day, then freezes a snapshot of the day as known now. */
    private void closeDay(int day) {
        for (Account a : accounts.values()) {
            assessFee(a.id(), day, day, "close of Day" + day, new ArrayList<>());
        }
        long cutoff = lastSequence();
        LedgerView view = liveView();
        for (Account a : accounts.values()) {
            Money ledger = view.balance(a.id(), day, cutoff);
            Money holds = view.activeHolds(a.id(), day, cutoff);
            append(new DayClose(nextSequence(), day, a.id(), day, ledger, holds, ledger.minus(holds), cutoff));
        }
        checkpoints.put("close:Day" + day, lastSequence());
        lastClosedDay = day;
    }

    /**
     * Charges the overdraft fee for one account and day if that day's closing ledger balance, as known
     * right now, is negative and no fee exists yet for that account and day. The fee is dated to that day.
     */
    private void assessFee(String accountId, int day, int recordedDay, String cause, List<Long> effects) {
        Account account = accounts.get(accountId);
        Money close = liveView().balance(accountId, day, lastSequence());
        if (!close.isNegative()) {
            return;
        }
        String key = "FEE|" + accountId + "|D" + day;
        Money tariff = feeTariffs.get(account.currency());
        if (tariff == null) {
            if (missingFeePolicyKeys.add(key)) {
                effects.add(append(new FeePolicyError(nextSequence(), recordedDay, accountId, day,
                        ErrorCode.MISSING_FEE_POLICY, close, cause)).sequence());
            }
            return;
        }
        if (feeKeys.add(key)) {
            effects.add(append(new Posting(nextSequence(), recordedDay, accountId, PostingType.OVERDRAFT_FEE,
                    tariff.negate(), day, null, key, null, cause)).sequence());
        }
    }

    /** After a backdated posting, re-check every already-closed day from its value day, oldest first. */
    private void reassessClosedDays(String accountId, int fromValueDay, String sourceId, List<Long> effects) {
        for (int d = fromValueDay; d <= lastClosedDay; d++) {
            assessFee(accountId, d, watermark, "re-assessment after " + sourceId, effects);
        }
    }

    /** Called after an event's money is posted: marks the cutoff, then re-checks closed days. */
    private void afterPrincipal(SourceEvent e, String accountId, int valueDay, List<Long> effects) {
        checkpoints.putIfAbsent("principal:" + e.sourceId(), lastSequence());
        reassessClosedDays(accountId, valueDay, e.sourceId(), effects);
    }

    // ------------------------------------------------------------------ event handling

    private record Applied(Outcome.Status status, ErrorCode code, String message, List<Long> effects) {
    }

    private Applied apply(SourceEvent e) {
        return switch (e.kind()) {
            case CREDIT, DEBIT -> applyCreditOrDebit(e);
            case AUTHORIZATION -> applyAuthorization(e);
            case SETTLEMENT -> applySettlement(e);
            case REVERSAL -> applyReversal(e);
            case INSTALMENT_CREDIT -> applyInstalmentCredit(e);
        };
    }

    /**
     * Cancels an earlier accepted CREDIT or DEBIT by appending an equal and opposite posting with the same
     * value day, linked to the original. The original posting is never edited or removed. Fees that the
     * original caused are not refunded: the brief gives no refund rule.
     */
    private Applied applyReversal(SourceEvent e) {
        Account account = requireAccount(e);
        requireValueDay(e);
        String targetId = e.targetSourceId();
        SourceEvent target = targetId == null ? null : sourcesById.get(targetId);
        if (target == null || targetId.equals(e.sourceId())) {
            throw new DomainException(ErrorCode.REVERSAL_TARGET_NOT_FOUND, "No source event " + targetId + " to reverse");
        }
        Outcome targetOutcome = outcomesById.get(targetId);
        if (targetOutcome == null || targetOutcome.status() != Outcome.Status.ACCEPTED) {
            throw new DomainException(ErrorCode.REVERSAL_TARGET_NOT_ACCEPTED, targetId + " was not accepted; nothing to reverse");
        }
        if (target.kind() != EventKind.CREDIT && target.kind() != EventKind.DEBIT) {
            throw new DomainException(ErrorCode.REVERSAL_UNSUPPORTED_TARGET,
                    "Only a CREDIT or DEBIT can be reversed, not " + target.kind());
        }
        if (!target.accountId().equals(account.id())) {
            throw new DomainException(ErrorCode.REVERSAL_ACCOUNT_MISMATCH,
                    targetId + " is on " + target.accountId() + ", not " + account.id());
        }
        Posting original = findPrincipalPosting(targetId);
        if (original.valueDay() != e.valueDay()) {
            throw new DomainException(ErrorCode.REVERSAL_VALUE_DAY_MISMATCH,
                    "Reversal value Day" + e.valueDay() + " must equal " + targetId + " value Day" + original.valueDay());
        }
        if (reversedSourceIds.contains(targetId)) {
            throw new DomainException(ErrorCode.REVERSAL_ALREADY_APPLIED, targetId + " was already reversed");
        }
        // --- validation complete
        reversedSourceIds.add(targetId);
        List<Long> effects = new ArrayList<>();
        effects.add(append(new Posting(nextSequence(), watermark, account.id(), PostingType.REVERSAL,
                original.amount().negate(), original.valueDay(), e.sourceId(), "REV|" + targetId,
                original.sequence(), e.sourceId() + " reverses " + targetId)).sequence());
        afterPrincipal(e, account.id(), original.valueDay(), effects);
        return new Applied(Outcome.Status.ACCEPTED, null, "reversed " + targetId + " with "
                + original.amount().negate() + " value Day" + original.valueDay() + "; fees already assessed are kept",
                effects);
    }

    /**
     * Credits a total as N child postings that sum exactly to it. The split is done in whole minor units;
     * the first {@code remainder} children get one extra unit (10.000 BHD / 3 = 3.334, 3.333, 3.333).
     * The parent event itself is not a posting. All children are validated before any is written.
     */
    private Applied applyInstalmentCredit(SourceEvent e) {
        Account account = requireAccount(e);
        Money total = parsePositiveAmount(account, e.currencyText(), e.amountText());
        requireValueDay(e);
        int count = e.instalmentCount();
        BigInteger totalMinor = total.minorUnits();
        if (count < 1 || BigInteger.valueOf(count).compareTo(totalMinor) > 0) {
            throw new DomainException(ErrorCode.INVALID_INSTALMENT_COUNT,
                    "Instalment count " + count + " must be between 1 and " + totalMinor + " minor units");
        }
        BigInteger[] qr = totalMinor.divideAndRemainder(BigInteger.valueOf(count));
        int remainder = qr[1].intValueExact();
        List<Money> children = new ArrayList<>();
        Money check = Money.zero(account.currency());
        for (int i = 0; i < count; i++) {
            Money child = Money.ofMinor(account.currency(), i < remainder ? qr[0].add(BigInteger.ONE) : qr[0]);
            children.add(child);
            check = check.plus(child);
        }
        if (check.compareTo(total) != 0) {
            throw new IllegalStateException("Instalments " + children + " do not sum to " + total);
        }
        // --- validation complete
        List<Long> effects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            effects.add(append(new Posting(nextSequence(), watermark, account.id(), PostingType.INSTALMENT_CREDIT,
                    children.get(i), e.valueDay(), e.sourceId(), e.sourceId() + "#" + (i + 1), null,
                    e.sourceId() + " instalment " + (i + 1) + "/" + count)).sequence());
        }
        afterPrincipal(e, account.id(), e.valueDay(), effects);
        List<String> parts = new ArrayList<>();
        for (Money c : children) {
            parts.add(c.plain());
        }
        return new Applied(Outcome.Status.ACCEPTED, null,
                "credited " + total + " as " + count + " instalments " + String.join(", ", parts), effects);
    }

    private Posting findPrincipalPosting(String sourceId) {
        for (LedgerRecord r : records) {
            if (r instanceof Posting p && sourceId.equals(p.sourceId())
                    && (p.type() == PostingType.EXTERNAL_CREDIT || p.type() == PostingType.EXTERNAL_DEBIT)) {
                return p;
            }
        }
        throw new IllegalStateException("Accepted " + sourceId + " has no principal posting");
    }

    /**
     * Approves a hold only if ledger - active holds - requested hold >= 0, using everything known now.
     * Either way an AuthorizationFact records the decision and the numbers it was based on.
     */
    private Applied applyAuthorization(SourceEvent e) {
        Account account = requireAccount(e);
        Money hold = parsePositiveAmount(account, e.currencyText(), e.amountText());
        requireValueDay(e);
        String authId = requireAuthorizationId(e);
        if (liveView().latestAuthorizationFact(authId, lastSequence()).isPresent()) {
            throw new DomainException(ErrorCode.DUPLICATE_AUTHORIZATION_ID, "Authorization " + authId + " already exists");
        }
        // --- validation complete
        LedgerView view = liveView();
        Money ledger = view.balance(account.id(), watermark, lastSequence());
        Money activeHolds = view.activeHolds(account.id(), watermark, lastSequence());
        Money remaining = ledger.minus(activeHolds).minus(hold);
        boolean approved = remaining.signum() >= 0;
        AuthorizationFact fact = append(new AuthorizationFact(nextSequence(), watermark, authId, account.id(),
                e.sourceId(), approved ? AuthorizationFact.Kind.APPROVED : AuthorizationFact.Kind.DECLINED,
                hold, ledger, activeHolds, null));
        String evidence = "ledger " + ledger.plain() + " - active holds " + activeHolds.plain() + " - requested "
                + hold.plain() + " = " + remaining.plain();
        if (approved) {
            return new Applied(Outcome.Status.ACCEPTED, null,
                    authId + " APPROVED, hold " + hold + " (" + evidence + " >= 0)", List.of(fact.sequence()));
        }
        return new Applied(Outcome.Status.DECLINED, ErrorCode.INSUFFICIENT_AVAILABLE_FUNDS,
                authId + " DECLINED, no hold created (" + evidence + " < 0)", List.of(fact.sequence()));
    }

    /**
     * One final capture against an approved, still-active authorization on the same account. Debits the
     * captured amount and releases the full hold in the same step. The new-authorization solvency rule is
     * deliberately not applied again: the hold already reserved the money.
     */
    private Applied applySettlement(SourceEvent e) {
        Account account = requireAccount(e);
        Money capture = parsePositiveAmount(account, e.currencyText(), e.amountText());
        requireValueDay(e);
        String authId = requireAuthorizationId(e);
        AuthorizationFact latest = liveView().latestAuthorizationFact(authId, lastSequence())
                .orElseThrow(() -> new DomainException(ErrorCode.UNKNOWN_AUTHORIZATION,
                        "No authorization " + authId + " exists; settlement refused, no funds moved"));
        if (!latest.accountId().equals(account.id())) {
            throw new DomainException(ErrorCode.AUTHORIZATION_ACCOUNT_MISMATCH,
                    authId + " belongs to " + latest.accountId() + ", not " + account.id());
        }
        switch (latest.kind()) {
            case DECLINED -> throw new DomainException(ErrorCode.AUTHORIZATION_DECLINED,
                    authId + " was declined; there is no hold to capture");
            case SETTLED -> throw new DomainException(ErrorCode.AUTHORIZATION_ALREADY_SETTLED,
                    authId + " was already settled (single final capture only)");
            case APPROVED -> { }
        }
        if (capture.compareTo(latest.hold()) > 0) {
            throw new DomainException(ErrorCode.CAPTURE_EXCEEDS_HOLD,
                    "Capture " + capture + " exceeds hold " + latest.hold());
        }
        // --- validation complete
        List<Long> effects = new ArrayList<>();
        effects.add(append(new Posting(nextSequence(), watermark, account.id(), PostingType.SETTLEMENT_DEBIT,
                capture.negate(), e.valueDay(), e.sourceId(), null, null, e.sourceId() + " capture " + authId)).sequence());
        effects.add(append(new AuthorizationFact(nextSequence(), watermark, authId, account.id(),
                e.sourceId(), AuthorizationFact.Kind.SETTLED, latest.hold(), null, null, capture)).sequence());
        afterPrincipal(e, account.id(), e.valueDay(), effects);
        return new Applied(Outcome.Status.ACCEPTED, null,
                authId + " settled: debited " + capture + ", released full hold " + latest.hold(), effects);
    }

    private Applied applyCreditOrDebit(SourceEvent e) {
        Account account = requireAccount(e);
        Money magnitude = parsePositiveAmount(account, e.currencyText(), e.amountText());
        requireValueDay(e);
        // --- validation complete; writes start here
        boolean credit = e.kind() == EventKind.CREDIT;
        List<Long> effects = new ArrayList<>();
        effects.add(append(new Posting(nextSequence(), watermark, account.id(),
                credit ? PostingType.EXTERNAL_CREDIT : PostingType.EXTERNAL_DEBIT,
                credit ? magnitude : magnitude.negate(), e.valueDay(), e.sourceId(), null, null, e.sourceId())).sequence());
        afterPrincipal(e, account.id(), e.valueDay(), effects);
        return new Applied(Outcome.Status.ACCEPTED, null,
                (credit ? "credited " : "debited ") + magnitude + " value Day" + e.valueDay(), effects);
    }

    // ------------------------------------------------------------------ validation helpers

    private Account requireAccount(SourceEvent e) {
        Account account = accounts.get(e.accountId());
        if (account == null) {
            throw new DomainException(ErrorCode.UNKNOWN_ACCOUNT, "Unknown account " + e.accountId());
        }
        return account;
    }

    /** A value day must be inside the window and not after the engine's clock (no future dating). */
    private void requireValueDay(SourceEvent e) {
        if (e.valueDay() < FIRST_DAY || e.valueDay() > LAST_DAY || e.valueDay() > watermark) {
            throw new DomainException(ErrorCode.INVALID_VALUE_DAY,
                    "Value day " + e.valueDay() + " must be within Day1..Day" + watermark + " (no future dating)");
        }
    }

    private static String requireAuthorizationId(SourceEvent e) {
        if (e.authorizationId() == null || e.authorizationId().isBlank()) {
            throw new DomainException(ErrorCode.MISSING_AUTHORIZATION_ID, "Authorization id is required");
        }
        return e.authorizationId();
    }

    /** Validates currency and a strictly positive magnitude at exactly the currency's precision. */
    private static Money parsePositiveAmount(Account account, String currencyText, String amountText) {
        CurrencyCode currency = CurrencyCode.fromCodeOrNull(currencyText);
        if (currency == null) {
            throw new DomainException(ErrorCode.UNSUPPORTED_CURRENCY, "Unsupported currency " + currencyText);
        }
        if (currency != account.currency()) {
            throw new DomainException(ErrorCode.CURRENCY_MISMATCH,
                    "Account " + account.id() + " is " + account.currency() + ", event is " + currency);
        }
        if (amountText == null) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT, "Amount is required");
        }
        BigDecimal raw;
        try {
            raw = new BigDecimal(amountText.trim());
        } catch (NumberFormatException ex) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT, "Not a decimal number: '" + amountText + "'");
        }
        Money money;
        try {
            money = new Money(currency, raw);
        } catch (ArithmeticException ex) {
            throw new DomainException(ErrorCode.EXCESS_PRECISION,
                    amountText + " has more than " + currency.scale() + " decimals for " + currency);
        }
        if (money.isNegative()) {
            throw new DomainException(ErrorCode.NEGATIVE_AMOUNT, "Amounts are non-negative magnitudes; got " + amountText);
        }
        if (money.isZero()) {
            throw new DomainException(ErrorCode.ZERO_AMOUNT, "Amount must be greater than zero");
        }
        return money;
    }

    // ------------------------------------------------------------------ log helpers

    private LedgerView liveView() {
        return new LedgerView(accounts, records);
    }

    private long nextSequence() {
        return records.size() + 1L;
    }

    private <T extends LedgerRecord> T append(T record) {
        if (record.sequence() != nextSequence()) {
            throw new IllegalStateException("Out-of-order sequence " + record.sequence());
        }
        records.add(record);
        return record;
    }

    private Outcome appendOutcome(SourceEvent e, int inputPosition, Outcome.Status status, ErrorCode code,
                                  String message, List<Long> effects) {
        return append(new Outcome(nextSequence(), watermark, e.sourceId(), inputPosition, e.kind(), status, code,
                message, effects));
    }
}
