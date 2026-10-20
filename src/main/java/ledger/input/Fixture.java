package ledger.input;

import java.util.List;
import ledger.money.CurrencyCode;
import ledger.money.Money;

/**
 * The exact input from the brief, in the exact order supplied ("replayed in this order"). It is
 * deliberately NOT sorted: E10 (booked Day 5) arrives after E9 (booked Day 6).
 */
public final class Fixture {

    public static final String ACC_AED = "ACC-001";
    public static final String ACC_BHD = "ACC-002";

    private Fixture() {
    }

    public static List<Account> accounts() {
        return List.of(
                new Account(ACC_AED, CurrencyCode.AED, Money.of(CurrencyCode.AED, "0.00")),
                new Account(ACC_BHD, CurrencyCode.BHD, Money.of(CurrencyCode.BHD, "0.000")));
    }

    public static List<SourceEvent> events() {
        return List.of(
                SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1),
                SourceEvent.debit("E2", 1, ACC_AED, "AED", "950.00", 1),
                SourceEvent.authorization("E3", 2, ACC_AED, "Auth-A", "AED", "200.00", 2),
                SourceEvent.credit("E4", 3, ACC_AED, "AED", "400.00", 3),
                SourceEvent.settlement("E5", 4, ACC_AED, "Auth-A", "AED", "185.00", 4),
                // E6: "Auth-Z has no preceding authorization event".
                SourceEvent.settlement("E6", 4, ACC_AED, "Auth-Z", "AED", "180.00", 4),
                // E7: booked Day 5 but value-dated back to Day 2.
                SourceEvent.debit("E7", 5, ACC_AED, "AED", "620.00", 2),
                SourceEvent.authorization("E8", 5, ACC_AED, "Auth-B", "AED", "90.00", 5),
                SourceEvent.reversal("E9", 6, ACC_AED, "E7", 2),
                // E10: the brief's CREDIT "posted as three equal instalments"; booked Day 5 but listed after E9.
                SourceEvent.instalmentCredit("E10", 5, ACC_BHD, "BHD", "10.000", 5, 3));
    }
}
