package ledger.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import ledger.input.Account;
import ledger.input.EventKind;
import ledger.input.SourceEvent;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import ledger.record.AuthorizationFact;
import ledger.record.ErrorCode;
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
 * seen — that only moves forward. A late event (booked earlier than the watermark) does not move it back.
 *
 * <p>Not thread-safe by design: one ordered writer only.
 */
public final class LedgerEngine {

    public static final int FIRST_DAY = 1;
    public static final int LAST_DAY = 6;

    private final Map<String, Account> accounts;

    private final List<LedgerRecord> records = new ArrayList<>();
    private final List<SourceEvent> inputLog = new ArrayList<>();
    private final Map<String, SourceEvent> sourcesById = new HashMap<>();
    private final Map<String, Outcome> outcomesById = new HashMap<>();

    private int watermark = FIRST_DAY;

    public LedgerEngine(List<Account> accounts) {
        Map<String, Account> byId = new TreeMap<>();
        for (Account a : accounts) {
            if (byId.put(a.id(), a) != null) {
                throw new IllegalArgumentException("Duplicate account " + a.id());
            }
        }
        this.accounts = Collections.unmodifiableMap(byId);
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
        if (event.bookedDay() < FIRST_DAY || event.bookedDay() > LAST_DAY) {
            outcome = appendOutcome(event, inputPosition, Outcome.Status.REJECTED, ErrorCode.INVALID_BOOKED_DAY,
                    "Booked day " + event.bookedDay() + " is outside Day1..Day6", List.of());
        } else {
            watermark = Math.max(watermark, event.bookedDay());
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
        return outcome;
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

    // ------------------------------------------------------------------ event handling

    private record Applied(Outcome.Status status, ErrorCode code, String message, List<Long> effects) {
    }

    private Applied apply(SourceEvent e) {
        return switch (e.kind()) {
            case CREDIT, DEBIT -> applyCreditOrDebit(e);
            case AUTHORIZATION -> applyAuthorization(e);
            case SETTLEMENT -> applySettlement(e);
            case REVERSAL, INSTALMENT_CREDIT ->
                    throw new UnsupportedOperationException(e.kind() + " is not implemented yet");
        };
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
        Posting debit = append(new Posting(nextSequence(), watermark, account.id(), PostingType.SETTLEMENT_DEBIT,
                capture.negate(), e.valueDay(), e.sourceId(), e.sourceId() + " capture " + authId));
        AuthorizationFact settled = append(new AuthorizationFact(nextSequence(), watermark, authId, account.id(),
                e.sourceId(), AuthorizationFact.Kind.SETTLED, latest.hold(), null, null, capture));
        return new Applied(Outcome.Status.ACCEPTED, null,
                authId + " settled: debited " + capture + ", released full hold " + latest.hold(),
                List.of(debit.sequence(), settled.sequence()));
    }

    private Applied applyCreditOrDebit(SourceEvent e) {
        Account account = requireAccount(e);
        Money magnitude = parsePositiveAmount(account, e.currencyText(), e.amountText());
        requireValueDay(e);
        // --- validation complete; writes start here
        boolean credit = e.kind() == EventKind.CREDIT;
        Posting posting = append(new Posting(nextSequence(), watermark, account.id(),
                credit ? PostingType.EXTERNAL_CREDIT : PostingType.EXTERNAL_DEBIT,
                credit ? magnitude : magnitude.negate(), e.valueDay(), e.sourceId(), e.sourceId()));
        return new Applied(Outcome.Status.ACCEPTED, null,
                (credit ? "credited " : "debited ") + magnitude + " value Day" + e.valueDay(),
                List.of(posting.sequence()));
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
