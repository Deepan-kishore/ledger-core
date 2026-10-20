package ledger.engine;

import static ledger.engine.Support.A;
import static ledger.engine.Support.aed;
import static ledger.engine.Support.availableNow;
import static ledger.engine.Support.fundedWith;
import static ledger.engine.Support.holdsNow;
import static ledger.engine.Support.now;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ledger.input.SourceEvent;
import ledger.record.AuthorizationFact;
import ledger.record.ErrorCode;
import ledger.record.Outcome;
import org.junit.jupiter.api.Test;

/** A hold is approved only if ledger - active holds - requested hold stays at or above zero. */
class AuthorizationTest {

    @Test
    void authAIsApprovedAndLeavesFiftyAvailable() {
        LedgerEngine engine = Support.fixtureThrough("E3");
        Outcome o = engine.outcome("E3").orElseThrow();
        assertEquals(Outcome.Status.ACCEPTED, o.status());
        assertEquals(aed("250.00"), now(engine, A, 2), "a hold does not change the ledger balance");
        assertEquals(aed("200.00"), holdsNow(engine, A, 2));
        assertEquals(aed("50.00"), availableNow(engine, A, 2), "250 - 0 - 200 = 50 >= 0");
        assertTrue(Support.postingsFor(engine, "E3").isEmpty(), "a hold is not a posting");
    }

    @Test
    void authBIsDeclinedInTheFixture() {
        LedgerEngine engine = Support.fixtureThrough("E8");
        Outcome o = engine.outcome("E8").orElseThrow();
        assertEquals(Outcome.Status.DECLINED, o.status());
        assertEquals(ErrorCode.INSUFFICIENT_AVAILABLE_FUNDS, o.errorCode());
        AuthorizationFact fact = engine.view().latestAuthorizationFact("Auth-B", engine.lastSequence()).orElseThrow();
        assertEquals(AuthorizationFact.Kind.DECLINED, fact.kind());
        // When E8 arrives, E7 (value Day 2) is posted and has already caused the Day 2 and Day 4 fees:
        // 1200 - 950 + 400 - 185 - 620 = -155, minus 2 x 25.00 = -205.
        assertEquals(aed("-205.00"), fact.ledgerAtDecision());
        assertEquals(aed("0.00"), fact.activeHoldsAtDecision(), "Auth-A was already released by E5");
        assertEquals(aed("0.00"), holdsNow(engine, A, 5), "a declined authorization creates no hold");
    }

    @Test
    void approvedWhenRemainingIsExactlyZeroAndDeclinedOneMinorUnitAbove() {
        LedgerEngine engine = fundedWith("100.00");
        assertEquals(Outcome.Status.ACCEPTED,
                engine.submit(SourceEvent.authorization("H1", 1, A, "X", "AED", "100.00", 1)).status(),
                "100 - 0 - 100 = 0 is 'at or above zero'");
        Outcome declined = engine.submit(SourceEvent.authorization("H2", 1, A, "Y", "AED", "0.01", 1));
        assertEquals(Outcome.Status.DECLINED, declined.status());
        assertEquals(aed("100.00"), holdsNow(engine, A, 1));
    }

    @Test
    void declineCreatesNoHoldButRecordsTheEvidence() {
        LedgerEngine engine = fundedWith("50.00");
        Outcome o = engine.submit(SourceEvent.authorization("H1", 1, A, "X", "AED", "50.01", 1));
        assertEquals(Outcome.Status.DECLINED, o.status());
        assertEquals(1, o.effectSequences().size(), "the decline fact is listed as the outcome's effect");
        assertEquals(aed("0.00"), holdsNow(engine, A, 1));
        AuthorizationFact fact = engine.view().latestAuthorizationFact("X", engine.lastSequence()).orElseThrow();
        assertEquals(aed("50.00"), fact.ledgerAtDecision());
        assertEquals(aed("50.01"), fact.hold());
    }

    @Test
    void multipleHoldsAccumulate() {
        LedgerEngine engine = fundedWith("100.00");
        assertEquals(Outcome.Status.ACCEPTED,
                engine.submit(SourceEvent.authorization("H1", 1, A, "X1", "AED", "30.00", 1)).status());
        assertEquals(Outcome.Status.ACCEPTED,
                engine.submit(SourceEvent.authorization("H2", 1, A, "X2", "AED", "40.00", 1)).status());
        assertEquals(Outcome.Status.DECLINED,
                engine.submit(SourceEvent.authorization("H3", 1, A, "X3", "AED", "30.01", 1)).status(),
                "100 - 70 - 30.01 < 0");
        assertEquals(Outcome.Status.ACCEPTED,
                engine.submit(SourceEvent.authorization("H4", 1, A, "X4", "AED", "30.00", 1)).status());
        assertEquals(aed("100.00"), holdsNow(engine, A, 1));
        assertEquals(aed("0.00"), availableNow(engine, A, 1));
    }

    @Test
    void criterion5HoldWouldLowerAvailableButNotLedger() {
        // Auth-B is declined in the fixture; this shows the conditional with a funded account.
        LedgerEngine engine = fundedWith("300.00");
        engine.submit(SourceEvent.authorization("H1", 1, A, "Auth-B", "AED", "90.00", 1));
        assertEquals(aed("300.00"), now(engine, A, 1));
        assertEquals(aed("210.00"), availableNow(engine, A, 1));
    }

    @Test
    void duplicateOrMissingAuthorizationIdIsRejected() {
        LedgerEngine engine = fundedWith("100.00");
        engine.submit(SourceEvent.authorization("H1", 1, A, "X", "AED", "10.00", 1));
        assertEquals(ErrorCode.DUPLICATE_AUTHORIZATION_ID,
                engine.submit(SourceEvent.authorization("H2", 1, A, "X", "AED", "10.00", 1)).errorCode());
        assertEquals(ErrorCode.MISSING_AUTHORIZATION_ID,
                engine.submit(SourceEvent.authorization("H3", 1, A, " ", "AED", "10.00", 1)).errorCode());
        assertEquals(aed("10.00"), holdsNow(engine, A, 1), "only the first hold exists");
    }
}
