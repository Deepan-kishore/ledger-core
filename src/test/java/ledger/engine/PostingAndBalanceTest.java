package ledger.engine;

import static ledger.input.Fixture.ACC_AED;
import static ledger.input.Fixture.ACC_BHD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ledger.input.Account;
import ledger.input.Fixture;
import ledger.input.SourceEvent;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import ledger.record.LedgerRecord;
import ledger.record.Outcome;
import ledger.record.Posting;
import ledger.record.PostingType;
import org.junit.jupiter.api.Test;

/** Credits and debits become signed postings; balances are summed by value day and knowledge cutoff. */
class PostingAndBalanceTest {

    private static Money aed(String s) {
        return Money.of(CurrencyCode.AED, s);
    }

    private static LedgerEngine engine() {
        return new LedgerEngine(Fixture.accounts(), LedgerEngine.standardFeeTariffs());
    }

    private static Money now(LedgerEngine engine, String account, int day) {
        return engine.view().balance(account, day, engine.lastSequence());
    }

    @Test
    void day1ClosesAt250() {
        LedgerEngine engine = engine();
        engine.submit(SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1));
        engine.submit(SourceEvent.debit("E2", 1, ACC_AED, "AED", "950.00", 1));
        assertEquals(aed("250.00"), now(engine, ACC_AED, 1));

        List<Posting> postings = engine.view().postings();
        assertEquals(List.of(aed("1200.00"), aed("-950.00")), postings.stream().map(Posting::amount).toList(),
                "credits are positive, debits negative");
        assertEquals(List.of(PostingType.EXTERNAL_CREDIT, PostingType.EXTERNAL_DEBIT),
                postings.stream().map(Posting::type).toList());
        assertEquals(Money.zero(CurrencyCode.BHD), now(engine, ACC_BHD, 1), "the other account is untouched");
    }

    @Test
    void backdatedDebitChangesItsValueDayAndLaterDaysButNotEarlierOnes() {
        LedgerEngine engine = engine();
        engine.submit(SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1));
        engine.submit(SourceEvent.debit("E2", 1, ACC_AED, "AED", "950.00", 1));
        engine.submit(SourceEvent.credit("E4", 3, ACC_AED, "AED", "400.00", 3));
        assertEquals(aed("250.00"), now(engine, ACC_AED, 2));

        // Like E7: booked Day 5, value-dated back to Day 2.
        engine.submit(SourceEvent.debit("E7", 5, ACC_AED, "AED", "620.00", 2));
        assertEquals(aed("250.00"), now(engine, ACC_AED, 1), "Day 1 is before the value day");
        assertEquals(aed("-370.00"), engine.view().balance(ACC_AED, 2, engine.checkpoint("principal:E7")),
                "250 - 620, before the fee E7 causes");
        assertEquals(aed("-395.00"), now(engine, ACC_AED, 2), "Day 2 went negative, so it gets a 25.00 fee");
        assertEquals(aed("5.00"), now(engine, ACC_AED, 3), "-395 + 400: the Day 2 fee counts on Day 3");

        Posting e7 = engine.view().postings().get(3);
        assertEquals(2, e7.valueDay(), "counts towards Day 2");
        assertEquals(5, e7.recordedDay(), "written on Day 5");
    }

    @Test
    void aCutoffShowsWhatWasKnownAtThatMoment() {
        LedgerEngine engine = engine();
        engine.submit(SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1));
        engine.submit(SourceEvent.debit("E2", 1, ACC_AED, "AED", "950.00", 1));
        long beforeBackdatedDebit = engine.lastSequence();
        engine.submit(SourceEvent.debit("E7", 5, ACC_AED, "AED", "620.00", 2));

        LedgerView view = engine.view();
        assertEquals(aed("250.00"), view.balance(ACC_AED, 2, beforeBackdatedDebit), "Day 2 as known before E7");
        assertEquals(aed("-370.00"), view.balance(ACC_AED, 2, engine.checkpoint("principal:E7")),
                "Day 2 right after E7's debit, before any fee");
        assertEquals(aed("-395.00"), view.balance(ACC_AED, 2, engine.lastSequence()), "Day 2 after E7's fee");
    }

    @Test
    void everyEventGetsAnOutcomeListingTheRecordsItCreated() {
        LedgerEngine engine = engine();
        Outcome o = engine.submit(SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1));
        assertEquals(Outcome.Status.ACCEPTED, o.status());
        assertEquals(1, o.inputPosition());
        assertEquals(List.of(1L), o.effectSequences(), "the posting is record #1");
        assertEquals(2L, o.sequence(), "the outcome itself is record #2");
        assertEquals(o, engine.outcome("E1").orElseThrow());
    }

    @Test
    void theLogIsAppendOnlyAndNumberedWithoutGaps() {
        LedgerEngine engine = engine();
        engine.submit(SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1));
        engine.submit(SourceEvent.debit("E2", 1, ACC_AED, "AED", "950.00", 1));
        List<LedgerRecord> records = engine.records();
        for (int i = 0; i < records.size(); i++) {
            assertEquals(i + 1L, records.get(i).sequence());
        }
        assertThrows(UnsupportedOperationException.class, () -> records.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> engine.inputLog().clear());
        assertThrows(UnsupportedOperationException.class, () -> engine.view().postings().clear());
        assertEquals(records, engine.records(), "outside changes are impossible, so the log is unchanged");
    }

    @Test
    void viewDoesNotChangeWhenTheCallersListChanges() {
        LedgerEngine engine = engine();
        engine.submit(SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1));
        List<LedgerRecord> log = new ArrayList<>(engine.records());
        Map<String, Account> accounts = Map.of(ACC_AED, Fixture.accounts().get(0));
        LedgerView view = new LedgerView(accounts, log);
        log.add(new Posting(log.size() + 1, 1, ACC_AED, PostingType.EXTERNAL_CREDIT, aed("1000.00"), 1, "X", null, "X"));
        assertEquals(aed("1200.00"), view.balance(ACC_AED, 1, Long.MAX_VALUE), "the view copied the list");
    }

    @Test
    void lateEventDoesNotMoveTheClockBack() {
        LedgerEngine engine = engine();
        engine.submit(SourceEvent.credit("A", 3, ACC_AED, "AED", "1.00", 3));
        engine.submit(SourceEvent.credit("B", 2, ACC_AED, "AED", "1.00", 2));
        assertEquals(3, engine.watermark());
        assertTrue(engine.view().postings().stream().allMatch(p -> p.recordedDay() == 3),
                "the late event is recorded on the day the engine had reached");
    }
}
