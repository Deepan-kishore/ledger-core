package ledger.record;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import ledger.money.Money;

/**
 * Calculation record for one account's window interest. This is report data, not money:
 * only the single capitalization {@link Posting} (if any) is spendable.
 *
 * @param basisCutoff knowledge cutoff of the balances used; excludes the capitalization credit itself
 * @param rawSum      exact sum of the raw daily accruals (shown for comparison only)
 * @param capitalized sum of the rounded daily accruals = the amount capitalized
 */
public record InterestReport(
        long sequence,
        int recordedDay,
        String accountId,
        long basisCutoff,
        List<DailyAccrual> days,
        BigDecimal rawSum,
        Money capitalized) implements LedgerRecord {

    /**
     * @param basis   the day's closing ledger balance, with everything known at the end of the window
     * @param raw     basis x 0.0004 if basis &gt; 0, else 0 (exact, more decimals than the currency)
     * @param rounded raw rounded HALF_EVEN to the currency's decimals: the day's accrual
     */
    public record DailyAccrual(int day, Money basis, BigDecimal raw, Money rounded) {
        public DailyAccrual {
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(raw, "raw");
            Objects.requireNonNull(rounded, "rounded");
        }
    }

    public InterestReport {
        Objects.requireNonNull(accountId, "accountId");
        days = List.copyOf(days);
        Objects.requireNonNull(rawSum, "rawSum");
        Objects.requireNonNull(capitalized, "capitalized");
    }
}
