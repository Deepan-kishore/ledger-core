package ledger.engine;

import static ledger.engine.Support.A;
import static ledger.engine.Support.B;
import static ledger.engine.Support.aed;
import static ledger.engine.Support.now;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import ledger.input.SourceEvent;
import ledger.record.AuthorizationFact;
import ledger.record.ErrorCode;
import ledger.record.Outcome;
import ledger.record.Posting;
import ledger.record.PostingType;
import org.junit.jupiter.api.Test;

/** A reversal appends an equal and opposite posting; the original and the fees it caused stay (criterion 6). */
class ReversalTest {

    @Test
    void e9AppendsALinkedEqualAndOppositeCreditAndLeavesE7Untouched() {
        Posting e7Before = Support.postingsFor(Support.fixtureThrough("E8"), "E7").get(0);
        LedgerEngine engine = Support.fixtureThrough("E9");
        Posting e7After = Support.postingsFor(engine, "E7").get(0);
        assertEquals(e7Before, e7After, "the original debit is never edited or removed");

        List<Posting> rev = Support.postingsFor(engine, "E9");
        assertEquals(1, rev.size());
        Posting r = rev.get(0);
        assertEquals(PostingType.REVERSAL, r.type());
        assertEquals(aed("620.00"), r.amount());
        assertEquals(0, r.amount().plus(e7After.amount()).signum(), "equal and opposite");
        assertEquals(2, r.valueDay(), "same value day as E7");
        assertEquals(6, r.recordedDay());
        assertEquals(e7After.sequence(), r.reversesSequence(), "linked to the posting it cancels");
        assertEquals("REV|E7", r.key());
    }

    @Test
    void e9ClosesDay5FirstSoItsFeeIsAlreadyCharged() {
        LedgerEngine engine = Support.fixtureThrough("E9");
        assertEquals(aed("-230.00"), engine.view().balance(A, 5, engine.checkpoint("close:Day5")),
                "Day 5 closed at -205 and was charged before E9 was applied");
        assertTrue(engine.checkpoint("close:Day5") < engine.checkpoint("principal:E9"));
    }

    @Test
    void criterion6FeesAreKeptAfterTheReversal() {
        LedgerEngine engine = Support.fixtureThrough("E9");
        assertEquals(aed("-75.00"), engine.view().total(A, PostingType.OVERDRAFT_FEE), "Days 2, 4 and 5");
        assertEquals(aed("390.00"), now(engine, A, 6), "465 before E7, minus 75 in fees");
        // Before E7 the days were 250 / 650 / 465 / 465; now 25 / 25 / 50 / 75 lower, so they do not return.
        assertEquals(aed("225.00"), now(engine, A, 2));
        assertEquals(aed("625.00"), now(engine, A, 3));
        assertEquals(aed("415.00"), now(engine, A, 4));
        assertEquals(aed("390.00"), now(engine, A, 5));
        assertEquals(AuthorizationFact.Kind.DECLINED,
                engine.view().latestAuthorizationFact("Auth-B", engine.lastSequence()).orElseThrow().kind(),
                "Auth-B is not reconsidered when E9 restores the money");
    }

    private static void assertRejected(LedgerEngine engine, SourceEvent e, ErrorCode code) {
        long postings = engine.view().postings().size();
        Outcome o = engine.submit(e);
        assertEquals(Outcome.Status.REJECTED, o.status(), e.sourceId());
        assertEquals(code, o.errorCode(), e.sourceId());
        assertEquals(postings, engine.view().postings().size(), e.sourceId());
    }

    @Test
    void invalidAndDuplicateReversalsAreRejectedWithoutPostings() {
        LedgerEngine engine = Support.fixtureThrough("E10");
        assertRejected(engine, SourceEvent.reversal("V1", 6, A, "E7", 2), ErrorCode.REVERSAL_ALREADY_APPLIED);
        assertRejected(engine, SourceEvent.reversal("V2", 6, A, "E99", 2), ErrorCode.REVERSAL_TARGET_NOT_FOUND);
        assertRejected(engine, SourceEvent.reversal("V3", 6, A, "E6", 4), ErrorCode.REVERSAL_TARGET_NOT_ACCEPTED);
        assertRejected(engine, SourceEvent.reversal("V4", 6, A, "E5", 4), ErrorCode.REVERSAL_UNSUPPORTED_TARGET);
        assertRejected(engine, SourceEvent.reversal("V5", 6, A, "E3", 2), ErrorCode.REVERSAL_UNSUPPORTED_TARGET);
        assertRejected(engine, SourceEvent.reversal("V6", 6, A, "E9", 2), ErrorCode.REVERSAL_UNSUPPORTED_TARGET);
        assertRejected(engine, SourceEvent.reversal("V7", 6, B, "E10", 5), ErrorCode.REVERSAL_UNSUPPORTED_TARGET);
        assertRejected(engine, SourceEvent.reversal("V8", 6, A, "E4", 2), ErrorCode.REVERSAL_VALUE_DAY_MISMATCH);
        assertRejected(engine, SourceEvent.reversal("V9", 6, B, "E4", 3), ErrorCode.REVERSAL_ACCOUNT_MISMATCH);
        assertRejected(engine, SourceEvent.reversal("V10", 6, A, "FEE|ACC-001|D2", 2),
                ErrorCode.REVERSAL_TARGET_NOT_FOUND);
        assertRejected(engine, SourceEvent.reversal("V11", 6, A, "V11", 2), ErrorCode.REVERSAL_TARGET_NOT_FOUND);
        assertEquals(aed("-75.00"), engine.view().total(A, PostingType.OVERDRAFT_FEE), "no fee can be reversed");
    }

    @Test
    void aCreditCanBeReversedToo() {
        LedgerEngine engine = Support.fixtureThrough("E10");
        Outcome o = engine.submit(SourceEvent.reversal("V1", 6, A, "E4", 3));
        assertEquals(Outcome.Status.ACCEPTED, o.status());
        assertEquals(aed("-400.00"), Support.postingsFor(engine, "V1").get(0).amount());
        assertTrue(now(engine, A, 6).isNegative());
    }
}
