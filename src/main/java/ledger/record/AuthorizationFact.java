package ledger.record;

import java.util.Objects;
import ledger.money.Money;

/**
 * Immutable evidence about an authorization (a "hold"). A hold reserves money so the customer cannot
 * spend it twice, but it is not a posting: it lowers the AVAILABLE balance, never the LEDGER balance.
 *
 * <ul>
 *   <li>APPROVED: the hold became active. Evidence fields show the numbers used for the decision.</li>
 *   <li>DECLINED: no hold was created. Same evidence fields.</li>
 *   <li>SETTLED: the one final capture happened; {@code captured} was debited and the full
 *       {@code hold} was released in the same step.</li>
 * </ul>
 * Later postings never edit these facts: a decision is judged by what was known when it was made.
 */
public record AuthorizationFact(
        long sequence,
        int recordedDay,
        String authorizationId,
        String accountId,
        String sourceId,
        Kind kind,
        Money hold,
        Money ledgerAtDecision,
        Money activeHoldsAtDecision,
        Money captured) implements LedgerRecord {

    public enum Kind { APPROVED, DECLINED, SETTLED }

    public AuthorizationFact {
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(hold, "hold");
    }
}
