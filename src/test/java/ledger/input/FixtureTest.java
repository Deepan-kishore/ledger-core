package ledger.input;

import static ledger.input.Fixture.ACC_AED;
import static ledger.input.Fixture.ACC_BHD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import org.junit.jupiter.api.Test;

/** The exact ten-event fixture and the two accounts, as given in the brief. */
class FixtureTest {

    @Test
    void fixtureHasExactlyTenEventsInSuppliedOrder() {
        List<SourceEvent> events = Fixture.events();
        assertEquals(List.of("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9", "E10"),
                events.stream().map(SourceEvent::sourceId).toList());
        assertEquals(List.of(1, 1, 2, 3, 4, 4, 5, 5, 6, 5),
                events.stream().map(SourceEvent::bookedDay).toList(), "E10 (Day5) stays after E9 (Day6): not sorted");
    }

    @Test
    void everyEventMatchesTheBrief() {
        List<SourceEvent> e = Fixture.events();
        assertEquals(SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1), e.get(0));
        assertEquals(SourceEvent.debit("E2", 1, ACC_AED, "AED", "950.00", 1), e.get(1));
        assertEquals(SourceEvent.authorization("E3", 2, ACC_AED, "Auth-A", "AED", "200.00", 2), e.get(2));
        assertEquals(SourceEvent.credit("E4", 3, ACC_AED, "AED", "400.00", 3), e.get(3));
        assertEquals(SourceEvent.settlement("E5", 4, ACC_AED, "Auth-A", "AED", "185.00", 4), e.get(4));
        assertEquals(SourceEvent.settlement("E6", 4, ACC_AED, "Auth-Z", "AED", "180.00", 4), e.get(5));
        assertEquals(SourceEvent.debit("E7", 5, ACC_AED, "AED", "620.00", 2), e.get(6));
        assertEquals(SourceEvent.authorization("E8", 5, ACC_AED, "Auth-B", "AED", "90.00", 5), e.get(7));
        assertEquals(SourceEvent.reversal("E9", 6, ACC_AED, "E7", 2), e.get(8));
        assertEquals(SourceEvent.instalmentCredit("E10", 5, ACC_BHD, "BHD", "10.000", 5, 3), e.get(9));
    }

    @Test
    void backdatedEventsHaveValueDayBeforeBookedDay() {
        List<SourceEvent> backdated = Fixture.events().stream().filter(e -> e.valueDay() < e.bookedDay()).toList();
        assertEquals(List.of("E7", "E9"), backdated.stream().map(SourceEvent::sourceId).toList());
    }

    @Test
    void openingsAreZeroAtCurrencyScale() {
        List<Account> accounts = Fixture.accounts();
        assertEquals(ACC_AED, accounts.get(0).id());
        assertEquals(CurrencyCode.AED, accounts.get(0).currency());
        assertEquals("0.00", accounts.get(0).opening().plain());
        assertEquals(ACC_BHD, accounts.get(1).id());
        assertEquals(CurrencyCode.BHD, accounts.get(1).currency());
        assertEquals("0.000", accounts.get(1).opening().plain());
    }

    @Test
    void accountRejectsAnOpeningInAnotherCurrency() {
        assertThrows(IllegalArgumentException.class,
                () -> new Account("X", CurrencyCode.AED, Money.zero(CurrencyCode.BHD)));
    }
}
