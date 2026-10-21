package ledger.record;

import java.util.Objects;
import ledger.money.Money;

/**
 * The ORIGINAL close snapshot of one account for one day, frozen at the moment the day closed.
 * It is never edited when later backdated events change history; restated views are computed
 * separately from the postings with a later knowledge cutoff.
 *
 * <ul>
 *   <li>ledger: sum of postings (includes that day's overdraft fee, if any)</li>
 *   <li>activeHolds: approved, not-yet-settled authorizations</li>
 *   <li>available = ledger - activeHolds</li>
 * </ul>
 *
 * @param knowledgeCutoff    the last sequence included in this snapshot
 * @param postCapitalization true only for the Day 6 snapshot, which is taken after the interest credit
 */
public record DayClose(
        long sequence,
        int recordedDay,
        String accountId,
        int day,
        Money ledger,
        Money activeHolds,
        Money available,
        long knowledgeCutoff,
        boolean postCapitalization) implements LedgerRecord {

    public DayClose {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(activeHolds, "activeHolds");
        Objects.requireNonNull(available, "available");
    }
}
