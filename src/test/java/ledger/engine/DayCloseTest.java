package ledger.engine;

import static ledger.engine.Support.A;
import static ledger.engine.Support.B;
import static ledger.engine.Support.aed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import ledger.input.SourceEvent;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import ledger.record.DayClose;
import ledger.record.ErrorCode;
import ledger.record.FeePolicyError;
import ledger.record.LedgerRecord;
import ledger.record.PostingType;
import org.junit.jupiter.api.Test;

/** Days close when a later day's event arrives; each close is a frozen snapshot that is never edited. */
class DayCloseTest {

    private static DayClose close(LedgerEngine engine, String account, int day) {
        return engine.view().recordsOf(DayClose.class).stream()
                .filter(c -> c.accountId().equals(account) && c.day() == day).findFirst().orElseThrow();
    }

    @Test
    void originalClosesAreAsKnownOnEachDay() {
        LedgerEngine engine = Support.fixtureThrough("E8");
        engine.finish();
        List<String> expected = List.of("250.00", "250.00", "650.00", "465.00", "-230.00");
        for (int d = 1; d <= 5; d++) {
            assertEquals(aed(expected.get(d - 1)), close(engine, A, d).ledger(), "Day" + d);
        }
        assertEquals(aed("200.00"), close(engine, A, 2).activeHolds(), "Auth-A held on Day 2");
        assertEquals(aed("50.00"), close(engine, A, 2).available());
        assertEquals(aed("450.00"), close(engine, A, 3).available(), "Auth-A still held on Day 3");
        assertEquals(aed("0.00"), close(engine, A, 4).activeHolds(), "released by E5 on Day 4");
    }

    @Test
    void aClosedDaySnapshotIsNeverEditedByLaterBackdating() {
        LedgerEngine engine = Support.fixtureThrough("E7");
        assertEquals(aed("250.00"), close(engine, A, 2).ledger(), "Day 2 as known when it closed");
        assertEquals(aed("-395.00"), Support.now(engine, A, 2), "Day 2 as known now");
    }

    @Test
    void skippedDaysAreClosedInOrder() {
        LedgerEngine engine = Support.engine();
        engine.submit(SourceEvent.credit("C1", 1, A, "AED", "10.00", 1));
        engine.submit(SourceEvent.credit("C2", 4, A, "AED", "10.00", 4));
        assertEquals(3, engine.lastClosedDay());
        List<Integer> closedDays = engine.view().recordsOf(DayClose.class).stream()
                .filter(c -> c.accountId().equals(A)).map(DayClose::day).toList();
        assertEquals(List.of(1, 2, 3), closedDays);
        assertEquals(List.of(1, 2, 3), engine.view().recordsOf(DayClose.class).stream()
                .filter(c -> c.accountId().equals(A)).map(LedgerRecord::recordedDay).toList(),
                "each close is stamped with its own day");
    }

    @Test
    void negativeBhdCloseRecordsMissingFeePolicyOncePerDayAndNoFee() {
        LedgerEngine engine = Support.engine();
        engine.submit(SourceEvent.debit("B1", 1, B, "BHD", "1.000", 1));
        engine.submit(SourceEvent.credit("B2", 2, A, "AED", "1.00", 2)); // closes Day 1
        engine.submit(SourceEvent.credit("B3", 2, A, "AED", "1.00", 1)); // re-checks Day 1 for ACC-001 only
        List<FeePolicyError> errors = engine.view().recordsOf(FeePolicyError.class);
        assertEquals(1, errors.size());
        assertEquals(ErrorCode.MISSING_FEE_POLICY, errors.get(0).code());
        assertEquals(1, errors.get(0).effectiveDay());
        assertEquals(Money.of(CurrencyCode.BHD, "-1.000"), errors.get(0).closingBalance());
        assertTrue(engine.view().postings(B, PostingType.OVERDRAFT_FEE).isEmpty(), "no BHD fee is invented");
    }

    @Test
    void finishClosesEveryRemainingDayOnceAndThenRejectsNewEvents() {
        LedgerEngine engine = Support.engine();
        engine.submit(SourceEvent.credit("C1", 2, A, "AED", "10.00", 2));
        engine.finish();
        assertEquals(6, engine.lastClosedDay());
        assertTrue(engine.isFinalized());
        List<LedgerRecord> before = engine.records();
        engine.finish();
        assertEquals(before, engine.records(), "a second finish adds nothing");
        assertEquals(ErrorCode.WINDOW_FINALIZED,
                engine.submit(SourceEvent.credit("LATE", 6, A, "AED", "1.00", 6)).errorCode());
    }
}
