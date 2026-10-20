package ledger.money;

import java.math.BigInteger;

/**
 * The two currencies in this assessment and how many decimal places each one posts with.
 * AED (UAE dirham) has 100 fils per dirham, so 2 decimals. BHD (Bahraini dinar) has
 * 1000 fils per dinar, so 3 decimals. A "minor unit" is that smallest coin: 0.01 AED or 0.001 BHD.
 */
public enum CurrencyCode {
    AED(2),
    BHD(3);

    private final int scale;

    CurrencyCode(int scale) {
        this.scale = scale;
    }

    /** Number of decimal places a posted amount must have. */
    public int scale() {
        return scale;
    }

    /** Minor units per major unit: 100 for AED, 1000 for BHD. */
    public BigInteger minorUnitsPerMajor() {
        return BigInteger.TEN.pow(scale);
    }

    /** Returns the enum for a code such as "AED", or null when the code is not supported. */
    public static CurrencyCode fromCodeOrNull(String code) {
        for (CurrencyCode c : values()) {
            if (c.name().equals(code)) {
                return c;
            }
        }
        return null;
    }
}
