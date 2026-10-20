package ledger.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** Precision, formatting, numeric comparison and currency safety of {@link Money}. */
class MoneyTest {

    private static Money aed(String s) {
        return Money.of(CurrencyCode.AED, s);
    }

    private static Money bhd(String s) {
        return Money.of(CurrencyCode.BHD, s);
    }

    @Test
    void amountsAreNormalizedToCurrencyScale() {
        assertEquals("1.00", aed("1").plain());
        assertEquals("1.50", aed("1.5").plain());
        assertEquals("1.500", bhd("1.5").plain());
        assertEquals("10.000", bhd("10").plain());
        assertEquals("AED -370.00", aed("-370").toString());
        assertEquals(2, aed("3").amount().scale());
        assertEquals(3, bhd("3").amount().scale());
    }

    @Test
    void excessPrecisionIsRejectedNotRounded() {
        assertThrows(ArithmeticException.class, () -> aed("1.005"));
        assertThrows(ArithmeticException.class, () -> bhd("1.0005"));
        assertEquals("1.00", aed("1.000").plain(), "trailing zeros are not excess precision");
    }

    @Test
    void arithmeticStaysExact() {
        assertEquals(aed("250.00"), aed("1200.00").minus(aed("950.00")));
        assertEquals(aed("-370.00"), aed("250.00").minus(aed("620.00")));
        assertEquals(bhd("10.000"), bhd("3.334").plus(bhd("3.333")).plus(bhd("3.333")));
        assertEquals(aed("25.00"), aed("-25.00").negate());
    }

    @Test
    void comparisonsAreNumericAndCurrencySafe() {
        assertEquals(0, aed("1.0").compareTo(aed("1.00")));
        assertEquals(aed("1.0"), aed("1.00"), "normalized scale makes equality safe");
        assertTrue(new BigDecimal("1.0").compareTo(new BigDecimal("1.00")) == 0
                && !new BigDecimal("1.0").equals(new BigDecimal("1.00")), "why raw BigDecimal.equals is avoided");
        assertThrows(IllegalArgumentException.class, () -> aed("1.00").plus(bhd("1.000")));
        assertThrows(IllegalArgumentException.class, () -> aed("1.00").compareTo(bhd("1.000")));
        assertEquals(-1, aed("-0.01").signum());
        assertTrue(aed("-0.01").isNegative());
        assertTrue(Money.zero(CurrencyCode.BHD).isZero());
    }

    @Test
    void minorUnitsRoundTrip() {
        assertEquals(BigInteger.valueOf(10000), bhd("10.000").minorUnits());
        assertEquals(BigInteger.valueOf(-37000), aed("-370.00").minorUnits());
        assertEquals(bhd("3.334"), Money.ofMinor(CurrencyCode.BHD, BigInteger.valueOf(3334)));
        assertEquals(BigInteger.valueOf(100), CurrencyCode.AED.minorUnitsPerMajor());
        assertEquals(BigInteger.valueOf(1000), CurrencyCode.BHD.minorUnitsPerMajor());
    }

    @Test
    void currencyCodeLookup() {
        assertEquals(CurrencyCode.AED, CurrencyCode.fromCodeOrNull("AED"));
        assertEquals(CurrencyCode.BHD, CurrencyCode.fromCodeOrNull("BHD"));
        assertNull(CurrencyCode.fromCodeOrNull("USD"));
        assertNull(CurrencyCode.fromCodeOrNull("aed"), "codes are exact, not case-folded");
    }
}
