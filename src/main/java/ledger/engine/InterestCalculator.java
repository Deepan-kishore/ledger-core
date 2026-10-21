package ledger.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import ledger.record.InterestReport;

/**
 * Turns six daily closing balances into six rounded daily accruals and one capitalized total.
 *
 * <p>Steps (all exact BigDecimal, no MathContext needed because 0.0004 terminates):
 * <ol>
 *   <li>raw(day) = balance x 0.0004 when balance &gt; 0, else 0. Never divided by 365: the rate is per day.</li>
 *   <li>rounded(day) = raw rounded HALF_EVEN to the currency's decimals. This is the day's accrual.</li>
 *   <li>capitalized = sum of the six rounded accruals.</li>
 * </ol>
 * The capitalized total is defined as the sum of the rounded accruals, so the two always agree exactly:
 * there is never a remainder to discard or to move between days. The exact raw sum is reported alongside
 * so the effect of rounding each day stays visible.
 */
public final class InterestCalculator {

    /** 0.04% per day = 0.0004 = 1/2500. Built from a string, never a double. */
    public static final BigDecimal DAILY_RATE = new BigDecimal("0.0004");

    private InterestCalculator() {
    }

    public record Result(List<InterestReport.DailyAccrual> days, BigDecimal rawSum, Money capitalized) {
        public Result {
            days = List.copyOf(days);
        }
    }

    /** @param closingBalances index 0 is Day 1 */
    public static Result calculate(CurrencyCode currency, List<Money> closingBalances) {
        int scale = currency.scale();
        List<InterestReport.DailyAccrual> days = new ArrayList<>();
        BigDecimal rawSum = BigDecimal.ZERO;
        Money capitalized = Money.zero(currency);
        for (int i = 0; i < closingBalances.size(); i++) {
            Money balance = closingBalances.get(i);
            if (balance.currency() != currency) {
                throw new IllegalArgumentException("Balance currency " + balance.currency() + " != " + currency);
            }
            BigDecimal raw = balance.signum() > 0 ? balance.amount().multiply(DAILY_RATE) : BigDecimal.ZERO;
            Money rounded = new Money(currency, raw.setScale(scale, RoundingMode.HALF_EVEN));
            days.add(new InterestReport.DailyAccrual(i + 1, balance, raw, rounded));
            rawSum = rawSum.add(raw);
            capitalized = capitalized.plus(rounded);
        }
        return new Result(days, rawSum, capitalized);
    }
}
