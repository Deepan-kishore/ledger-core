package ledger.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An immutable amount of one currency, always held at exactly that currency's scale
 * (AED 2 decimals, BHD 3 decimals). The amount may be negative: postings are signed
 * (credits positive, debits negative) and balances can be overdrawn.
 *
 * <p>Rules this type enforces:
 * <ul>
 *   <li>Built from text or exact integers, never from float/double.</li>
 *   <li>Excess precision is rejected, not rounded: {@code setScale(scale, UNNECESSARY)} throws
 *       ArithmeticException for "1.005" AED instead of silently changing it.</li>
 *   <li>Arithmetic across currencies throws: AED and BHD are never added together.</li>
 * </ul>
 * Because the scale is normalized, record equality (which uses BigDecimal.equals and therefore
 * compares scale) is safe here; still, callers should prefer {@link #compareTo} / {@link #signum}.
 */
public record Money(CurrencyCode currency, BigDecimal amount) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        // Throws ArithmeticException if amount has non-zero digits beyond the currency scale.
        amount = amount.setScale(currency.scale(), RoundingMode.UNNECESSARY);
    }

    /** Parses a decimal string such as "1200.00". Throws NumberFormatException / ArithmeticException. */
    public static Money of(CurrencyCode currency, String text) {
        Objects.requireNonNull(text, "text");
        return new Money(currency, new BigDecimal(text.trim()));
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(currency, BigDecimal.ZERO);
    }

    /** Builds an amount from a count of minor units, e.g. 3334 BHD minor units -> 3.334. */
    public static Money ofMinor(CurrencyCode currency, BigInteger minorUnits) {
        return new Money(currency, new BigDecimal(minorUnits, currency.scale()));
    }

    /** Exact count of minor units, e.g. 10.000 BHD -> 10000. */
    public BigInteger minorUnits() {
        return amount.movePointRight(currency.scale()).toBigIntegerExact();
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, amount.add(other.amount));
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(currency, amount.negate());
    }

    public int signum() {
        return amount.signum();
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (other.currency != currency) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    /** Plain number at currency scale, e.g. "-370.00" or "3.334". */
    public String plain() {
        return amount.toPlainString();
    }

    @Override
    public String toString() {
        return currency + " " + amount.toPlainString();
    }
}
