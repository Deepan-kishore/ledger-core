package ledger.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ledger.input.Account;
import ledger.money.Money;
import ledger.record.AuthorizationFact;
import ledger.record.LedgerRecord;
import ledger.record.Posting;
import ledger.record.PostingType;

/**
 * A read-only projection computed by scanning the record log. It never appends anything, so it can
 * be rebuilt from {@link LedgerEngine#records()} at any time and must give the same answers.
 *
 * <p>Core formula:
 * <pre>
 * balance(account, effectiveDay, cutoff) =
 *     opening + sum(posting.amount where posting.valueDay &lt;= effectiveDay
 *                                    and posting.sequence &lt;= cutoff)
 * </pre>
 * Each query is a full scan: O(records). With 6 days and a few dozen records that is tiny, and it
 * keeps the logic obviously correct. A production system would index by account and value day.
 */
public final class LedgerView {

    private final Map<String, Account> accounts;
    private final List<LedgerRecord> records;
    /** Postings and authorization facts per account, in sequence order; null when built from a plain log. */
    private final Map<String, List<LedgerRecord>> byAccount;

    /** Copies both arguments, so later changes to the caller's collections never change this view. */
    public LedgerView(Map<String, Account> accounts, List<LedgerRecord> records) {
        this.accounts = Map.copyOf(accounts);
        this.records = List.copyOf(records);
        this.byAccount = null;
    }

    /**
     * Engine-internal view over the live log and the engine's per-account index. Nothing is copied: the engine
     * builds one of these for every check, and copying the whole log each time made replay quadratic.
     */
    LedgerView(Map<String, Account> accounts, List<LedgerRecord> records,
               Map<String, List<LedgerRecord>> byAccount) {
        this.accounts = accounts;
        this.records = records;
        this.byAccount = byAccount;
    }

    /** The records a per-account query has to look at: that account's list if indexed, else the whole log. */
    private List<LedgerRecord> recordsFor(String accountId) {
        return byAccount == null ? records : byAccount.getOrDefault(accountId, List.of());
    }

    public Account account(String accountId) {
        Account a = accounts.get(accountId);
        if (a == null) {
            throw new IllegalArgumentException("Unknown account " + accountId);
        }
        return a;
    }

    /** Ledger balance: opening plus postings effective on or before the day and known by the cutoff. */
    public Money balance(String accountId, int effectiveDay, long cutoff) {
        Money total = account(accountId).opening();
        for (LedgerRecord r : recordsFor(accountId)) {
            if (r.sequence() > cutoff) {
                break;
            }
            if (r instanceof Posting p && p.accountId().equals(accountId) && p.valueDay() <= effectiveDay) {
                total = total.plus(p.amount());
            }
        }
        return total;
    }

    /**
     * Sum of holds approved on or before {@code day} and not yet settled by {@code day}, using only facts
     * known by the cutoff. Holds follow the day they were recorded, not a value day: a hold cannot be
     * backdated.
     */
    public Money activeHolds(String accountId, int day, long cutoff) {
        Money total = Money.zero(account(accountId).currency());
        for (LedgerRecord r : recordsFor(accountId)) {
            if (r.sequence() > cutoff) {
                break;
            }
            if (r instanceof AuthorizationFact f && f.accountId().equals(accountId) && f.recordedDay() <= day) {
                if (f.kind() == AuthorizationFact.Kind.APPROVED) {
                    total = total.plus(f.hold());
                } else if (f.kind() == AuthorizationFact.Kind.SETTLED) {
                    total = total.minus(f.hold());
                }
            }
        }
        return total;
    }

    /** Available balance = ledger balance minus active holds. */
    public Money available(String accountId, int day, long cutoff) {
        return balance(accountId, day, cutoff).minus(activeHolds(accountId, day, cutoff));
    }

    /** Latest fact for an authorization id known by the cutoff (APPROVED, DECLINED or SETTLED). */
    public Optional<AuthorizationFact> latestAuthorizationFact(String authorizationId, long cutoff) {
        AuthorizationFact latest = null;
        for (LedgerRecord r : records) {
            if (r.sequence() > cutoff) {
                break;
            }
            if (r instanceof AuthorizationFact f && f.authorizationId().equals(authorizationId)) {
                latest = f;
            }
        }
        return Optional.ofNullable(latest);
    }

    public <T extends LedgerRecord> List<T> recordsOf(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (LedgerRecord r : records) {
            if (type.isInstance(r)) {
                out.add(type.cast(r));
            }
        }
        return List.copyOf(out);
    }

    public List<Posting> postings() {
        List<Posting> out = new ArrayList<>();
        for (LedgerRecord r : records) {
            if (r instanceof Posting p) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    public List<Posting> postings(String accountId, PostingType type) {
        List<Posting> out = new ArrayList<>();
        for (Posting p : postings()) {
            if (p.accountId().equals(accountId) && p.type() == type) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /** Sum of one posting type for an account (e.g. total overdraft fees, which are negative). */
    public Money total(String accountId, PostingType type) {
        Money total = Money.zero(account(accountId).currency());
        for (Posting p : postings(accountId, type)) {
            total = total.plus(p.amount());
        }
        return total;
    }
}
