package ledger.engine;

import static ledger.engine.Support.aed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import ledger.record.InterestReport;
import org.junit.jupiter.api.Test;

/** Each day rounded on its own; the capitalized total is their sum (criterion 8 refused). */
class InterestCalculatorTest {

    private static List<Money> aedList(String... amounts) {
        List<Money> out = new ArrayList<>();
        for (String a : amounts) {
            out.add(aed(a));
        }
        return out;
    }

    private static List<String> rounded(InterestCalculator.Result r) {
        return r.days().stream().map(d -> d.rounded().plain()).toList();
    }

    @Test
    void dailyRateIsExactlyOneTwentyFiveHundredth() {
        assertEquals(new BigDecimal("0.0004"), InterestCalculator.DAILY_RATE);
        assertEquals(0, InterestCalculator.DAILY_RATE.multiply(BigDecimal.valueOf(2500)).compareTo(BigDecimal.ONE));
    }

    @Test
    void fixtureDailyAccrualsSumExactlyToNinetyThreeFils() {
        InterestCalculator.Result r = InterestCalculator.calculate(CurrencyCode.AED,
                aedList("250.00", "225.00", "625.00", "415.00", "390.00", "390.00"));
        List<String> raw = r.days().stream().map(d -> d.raw().stripTrailingZeros().toPlainString()).toList();
        assertEquals(List.of("0.1", "0.09", "0.25", "0.166", "0.156", "0.156"), raw);
        assertEquals(List.of("0.10", "0.09", "0.25", "0.17", "0.16", "0.16"), rounded(r),
                "every day is its own raw amount rounded; no day is adjusted to hit a target");
        assertEquals(aed("0.93"), r.capitalized());
        assertEquals(0, r.rawSum().compareTo(new BigDecimal("0.9180")), "exact sum kept for comparison only");
        Money sum = Money.zero(CurrencyCode.AED);
        for (InterestReport.DailyAccrual d : r.days()) {
            sum = sum.plus(d.rounded());
        }
        assertEquals(r.capitalized(), sum, "criterion 8 refused: nothing is discarded or moved");
    }

    @Test
    void zeroAndNegativeBalancesEarnNothing() {
        InterestCalculator.Result r = InterestCalculator.calculate(CurrencyCode.AED,
                aedList("0.00", "-500.00", "100.00"));
        assertEquals(0, r.days().get(0).raw().signum());
        assertEquals(0, r.days().get(1).raw().signum(), "no negative interest");
        assertEquals(List.of("0.00", "0.00", "0.04"), rounded(r));
    }

    @Test
    void dailyRoundingUsesHalfEvenOnExactHalves() {
        // 12.50 x 0.0004 = 0.005 exactly: HALF_EVEN -> 0.00 (HALF_UP would give 0.01)
        // 37.50 x 0.0004 = 0.015 exactly: HALF_EVEN -> 0.02
        InterestCalculator.Result r = InterestCalculator.calculate(CurrencyCode.AED, aedList("12.50", "37.50"));
        assertEquals(List.of("0.00", "0.02"), rounded(r));
        assertEquals(aed("0.02"), r.capitalized());
    }

    @Test
    void eachDayIsRoundedOnItsOwnEvenWhenTheExactSumDiffers() {
        // raw 0.009, 0.006, 0.008 -> each rounds to 0.01 -> capitalized 0.03, although the exact sum is 0.023.
        InterestCalculator.Result r = InterestCalculator.calculate(CurrencyCode.AED, aedList("22.50", "15.00", "20.00"));
        assertEquals(List.of("0.01", "0.01", "0.01"), rounded(r));
        assertEquals(aed("0.03"), r.capitalized());
        assertEquals(0, r.rawSum().compareTo(new BigDecimal("0.023")));
    }

    @Test
    void roundingCanAlsoLandBelowTheExactSum() {
        // raw 0.005 + 0.005 = 0.010 exactly, but each half rounds to the even 0.00 -> capitalized 0.00.
        InterestCalculator.Result r = InterestCalculator.calculate(CurrencyCode.AED, aedList("12.50", "12.50"));
        assertEquals(aed("0.00"), r.capitalized());
        assertTrue(r.rawSum().compareTo(r.capitalized().amount()) > 0, "per-day rounding does not always favour one side");
    }

    @Test
    void bhdUsesThreeDecimals() {
        InterestCalculator.Result r = InterestCalculator.calculate(CurrencyCode.BHD,
                List.of(Money.zero(CurrencyCode.BHD), Support.bhd("10.000"), Support.bhd("10.000")));
        assertEquals(Support.bhd("0.008"), r.capitalized());
        assertEquals(3, r.days().get(1).rounded().amount().scale());
    }

    @Test
    void mixedCurrencyInputIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class,
                () -> InterestCalculator.calculate(CurrencyCode.AED, List.of(Support.bhd("1.000"))));
    }
}
