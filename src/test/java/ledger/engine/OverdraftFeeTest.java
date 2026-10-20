package ledger.engine;

import static ledger.engine.Support.A;
import static ledger.engine.Support.aed;
import static ledger.engine.Support.now;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import ledger.input.SourceEvent;
import ledger.record.Outcome;
import ledger.record.Posting;
import ledger.record.PostingType;
import org.junit.jupiter.api.Test;

/** AED 25.00 once per account per day whose closing balance (value date <= that day) is negative. */
class OverdraftFeeTest {

    private static List<Posting> fees(LedgerEngine engine) {
        return engine.view().postings(A, PostingType.OVERDRAFT_FEE);
    }

    @Test
    void criterion1Day2IsMinus370AfterE7BeforeAnyFee() {
        LedgerEngine engine = Support.fixtureThrough("E7");
        long cutoff = engine.checkpoint("principal:E7");
        List<String> expected = List.of("250.00", "-370.00", "30.00", "-155.00", "-155.00");
        for (int d = 1; d <= 5; d++) {
            assertEquals(aed(expected.get(d - 1)), engine.view().balance(A, d, cutoff), "Day" + d);
        }
    }

    @Test
    void e7ImmediatelyCausesFeesForClosedDays2And4ButNot3() {
        LedgerEngine engine = Support.fixtureThrough("E7");
        List<Posting> fees = fees(engine);
        assertEquals(List.of(2, 4), fees.stream().map(Posting::valueDay).toList(), "each fee is dated to its day");
        assertEquals(List.of(5, 5), fees.stream().map(Posting::recordedDay).toList(), "both found on Day 5");
        assertTrue(fees.stream().allMatch(f -> f.amount().equals(aed("-25.00"))));
        assertEquals(List.of("FEE|ACC-001|D2", "FEE|ACC-001|D4"), fees.stream().map(Posting::key).toList());
        Outcome e7 = engine.outcome("E7").orElseThrow();
        assertTrue(e7.effectSequences().containsAll(fees.stream().map(Posting::sequence).toList()),
                "the fees are listed as effects of E7");
        assertEquals(aed("-395.00"), now(engine, A, 2), "-370 - 25");
        assertEquals(aed("5.00"), now(engine, A, 3), "-395 + 400: positive, so no Day 3 fee");
        assertEquals(aed("-205.00"), now(engine, A, 4), "5 - 185 = -180, then its fee");
    }

    @Test
    void theOpenDayIsChargedOnlyWhenItCloses() {
        LedgerEngine engine = Support.fixtureThrough("E8");
        assertTrue(now(engine, A, 5).isNegative(), "Day 5 is negative while still open");
        assertTrue(fees(engine).stream().noneMatch(f -> f.valueDay() == 5), "but not charged yet");
        engine.finish();
        Posting day5 = fees(engine).stream().filter(f -> f.valueDay() == 5).findFirst().orElseThrow();
        assertEquals(5, day5.recordedDay());
        assertEquals("close of Day5", day5.cause());
        assertEquals(aed("-230.00"), engine.view().balance(A, 5, engine.checkpoint("close:Day5")));
    }

    @Test
    void criterion2E7CausesThreeFeesNotOne() {
        LedgerEngine engine = Support.fixtureThrough("E8");
        engine.finish();
        List<Integer> feeDays = fees(engine).stream().map(Posting::valueDay).toList();
        assertTrue(feeDays.containsAll(List.of(2, 4, 5)), "Days 2, 4 and 5 all close negative because of E7");
        assertTrue(!feeDays.contains(3), "Day 3 escapes at +5.00");
    }

    @Test
    void aBalanceThatDipsAndRecoversBeforeTheCloseIsNotCharged() {
        LedgerEngine engine = Support.engine();
        engine.submit(SourceEvent.debit("T1", 1, A, "AED", "50.00", 1));
        assertEquals(aed("-50.00"), now(engine, A, 1));
        engine.submit(SourceEvent.credit("T2", 1, A, "AED", "100.00", 1));
        engine.submit(SourceEvent.credit("T3", 2, A, "AED", "1.00", 2)); // closes Day 1 at +50.00
        assertTrue(fees(engine).isEmpty());
    }

    @Test
    void anEarlierFeeCanTurnALaterSmallPositiveDayNegative() {
        // Day 1 +100, Day 2 +10. On Day 3 a debit of 105 dated Day 1 arrives.
        // Day 1 = -5 -> fee -> -30. Day 2 would be +5 without that fee, but is -20 with it -> second fee.
        LedgerEngine engine = Support.engine();
        engine.submit(SourceEvent.credit("P1", 1, A, "AED", "100.00", 1));
        engine.submit(SourceEvent.credit("P2", 2, A, "AED", "10.00", 2));
        engine.submit(SourceEvent.debit("P3", 3, A, "AED", "105.00", 1));
        assertEquals(aed("5.00"), engine.view().balance(A, 2, engine.checkpoint("principal:P3")),
                "Day 2 before any fee is positive");
        assertEquals(List.of(1, 2), fees(engine).stream().map(Posting::valueDay).toList());
        assertEquals(aed("-45.00"), now(engine, A, 2), "5 - 25 - 25");
    }

    @Test
    void repeatedBackdatingNeverChargesTheSameDayTwice() {
        LedgerEngine engine = Support.engine();
        engine.submit(SourceEvent.credit("R1", 1, A, "AED", "10.00", 1));
        engine.submit(SourceEvent.debit("R2", 3, A, "AED", "20.00", 1));
        engine.submit(SourceEvent.debit("R3", 3, A, "AED", "20.00", 1));
        engine.submit(SourceEvent.debit("R4", 4, A, "AED", "1.00", 1));
        assertEquals(List.of(1, 2, 3), fees(engine).stream().map(Posting::valueDay).toList(),
                "one fee per day, however many times it is re-checked");
    }

    @Test
    void zeroIsNotNegative() {
        LedgerEngine engine = Support.engine();
        engine.submit(SourceEvent.credit("Z1", 1, A, "AED", "10.00", 1));
        engine.submit(SourceEvent.debit("Z2", 1, A, "AED", "10.00", 1));
        engine.finish();
        assertTrue(fees(engine).isEmpty(), "a 0.00 close pays no fee");
    }
}
