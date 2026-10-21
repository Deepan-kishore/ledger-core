package ledger.engine;

import static ledger.engine.Support.A;
import static ledger.engine.Support.B;
import static ledger.engine.Support.aed;
import static ledger.engine.Support.bhd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import ledger.input.SourceEvent;
import ledger.record.DayClose;
import ledger.record.InterestReport;
import ledger.record.LedgerRecord;
import ledger.record.Posting;
import ledger.record.PostingType;
import org.junit.jupiter.api.Test;

/** One interest credit per account at the end of Day 6, computed on every day's balance as known then. */
class CapitalizationTest {

    private static InterestReport report(LedgerEngine engine, String account) {
        return engine.view().recordsOf(InterestReport.class).stream()
                .filter(r -> r.accountId().equals(account)).findFirst().orElseThrow();
    }

    @Test
    void exactlyOneCreditPerAccountDatedDay6() {
        LedgerEngine engine = Support.fullFixture();
        List<Posting> capA = engine.view().postings(A, PostingType.INTEREST_CAPITALIZATION);
        List<Posting> capB = engine.view().postings(B, PostingType.INTEREST_CAPITALIZATION);
        assertEquals(1, capA.size());
        assertEquals(1, capB.size());
        assertEquals(aed("0.93"), capA.get(0).amount());
        assertEquals(bhd("0.008"), capB.get(0).amount());
        assertEquals(6, capA.get(0).valueDay());
        assertEquals(aed("390.93"), Support.now(engine, A, 6));
        assertEquals(bhd("10.008"), Support.now(engine, B, 6));
    }

    @Test
    void interestUsesEachDaysBalanceWithEverythingKnownAtTheEnd() {
        LedgerEngine engine = Support.fullFixture();
        assertEquals(List.of("250.00", "225.00", "625.00", "415.00", "390.00", "390.00"),
                report(engine, A).days().stream().map(d -> d.basis().plain()).toList(),
                "after E7, its fees and E9: not the balances as known on each day (Day 5 was -230 then)");
        assertEquals(List.of("0.000", "0.000", "0.000", "0.000", "10.000", "10.000"),
                report(engine, B).days().stream().map(d -> d.basis().plain()).toList(),
                "late E10 is included in Day 5");
    }

    @Test
    void theCreditDoesNotEarnInterestOnItself() {
        LedgerEngine engine = Support.fullFixture();
        InterestReport a = report(engine, A);
        assertEquals(aed("390.00"), a.days().get(5).basis(), "not 390.93");
        Posting cap = engine.view().postings(A, PostingType.INTEREST_CAPITALIZATION).get(0);
        assertTrue(a.basisCutoff() < cap.sequence());
    }

    @Test
    void theDay6SnapshotIsTakenAfterTheCredit() {
        LedgerEngine engine = Support.fullFixture();
        DayClose day6 = engine.view().recordsOf(DayClose.class).stream()
                .filter(c -> c.accountId().equals(A) && c.day() == 6).findFirst().orElseThrow();
        assertTrue(day6.postCapitalization());
        assertEquals(aed("390.93"), day6.ledger());
        assertTrue(engine.view().recordsOf(DayClose.class).stream()
                .filter(c -> c.day() < 6).noneMatch(DayClose::postCapitalization));
    }

    @Test
    void interestIsEarnedOnTheLedgerBalanceNotAvailable() {
        // An approved hold that is never settled stays active through Day 6, but does not reduce interest.
        LedgerEngine engine = Support.fundedWith("100.00");
        engine.submit(SourceEvent.authorization("H1", 2, A, "X", "AED", "40.00", 2));
        engine.finish();
        DayClose day6 = engine.view().recordsOf(DayClose.class).stream()
                .filter(c -> c.accountId().equals(A) && c.day() == 6).findFirst().orElseThrow();
        assertEquals(aed("40.00"), day6.activeHolds(), "no expiry rule is given, so the hold stays");
        assertEquals(aed("100.24"), day6.ledger(), "100.00 x 0.0004 x 6 days = 0.24");
        assertEquals(aed("60.24"), day6.available());
    }

    @Test
    void zeroInterestPostsNoCreditButIsStillReported() {
        LedgerEngine engine = Support.engine();
        engine.finish();
        assertTrue(engine.view().postings().isEmpty());
        assertEquals(2, engine.view().recordsOf(InterestReport.class).size());
    }

    @Test
    void aSecondFinishAddsNothing() {
        LedgerEngine engine = Support.fullFixture();
        List<LedgerRecord> before = engine.records();
        engine.finish();
        assertEquals(before, engine.records());
    }
}
