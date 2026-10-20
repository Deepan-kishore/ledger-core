package ledger.engine;

import static ledger.engine.Support.A;
import static ledger.engine.Support.aed;
import static ledger.engine.Support.availableNow;
import static ledger.engine.Support.holdsNow;
import static ledger.engine.Support.now;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import ledger.input.SourceEvent;
import ledger.money.Money;
import ledger.record.AuthorizationFact;
import ledger.record.ErrorCode;
import ledger.record.Outcome;
import ledger.record.Posting;
import ledger.record.PostingType;
import org.junit.jupiter.api.Test;

/** One final capture per approved hold (criterion 3); settlements without an authorization are rejected (criterion 4). */
class SettlementTest {

    @Test
    void e5CapturesOneEightyFiveAndReleasesTheFullHold() {
        LedgerEngine engine = Support.fixtureThrough("E5");
        assertEquals(Outcome.Status.ACCEPTED, engine.outcome("E5").orElseThrow().status());
        List<Posting> postings = Support.postingsFor(engine, "E5");
        assertEquals(1, postings.size());
        assertEquals(PostingType.SETTLEMENT_DEBIT, postings.get(0).type());
        assertEquals(aed("-185.00"), postings.get(0).amount());
        assertEquals(aed("465.00"), now(engine, A, 4), "650 - 185");
        assertEquals(aed("0.00"), holdsNow(engine, A, 4), "the whole 200 hold is released, no 15 left over");
        assertEquals(aed("465.00"), availableNow(engine, A, 4));
        assertEquals(AuthorizationFact.Kind.SETTLED,
                engine.view().latestAuthorizationFact("Auth-A", engine.lastSequence()).orElseThrow().kind());
        assertFalse(engine.view().postings().stream()
                        .anyMatch(p -> p.amount().amount().abs().compareTo(new BigDecimal("15")) == 0),
                "the uncaptured 15.00 was only reserved, so there is nothing to refund");
    }

    @Test
    void e6UnknownAuthorizationIsRejectedAndNoFundsLeave() {
        LedgerEngine engine = Support.fixtureThrough("E6");
        Outcome o = engine.outcome("E6").orElseThrow();
        assertEquals(Outcome.Status.REJECTED, o.status());
        assertEquals(ErrorCode.UNKNOWN_AUTHORIZATION, o.errorCode());
        assertTrue(o.effectSequences().isEmpty());
        assertTrue(Support.postingsFor(engine, "E6").isEmpty());
        assertEquals(aed("465.00"), now(engine, A, 4), "unchanged by E6");
        assertTrue(engine.view().latestAuthorizationFact("Auth-Z", engine.lastSequence()).isEmpty(),
                "no authorization is invented for Auth-Z");
    }

    private static void assertRejectedWithoutChange(LedgerEngine engine, SourceEvent bad, ErrorCode code) {
        int day = engine.watermark();
        Money ledger = now(engine, bad.accountId(), day);
        Money holds = holdsNow(engine, A, day);
        long postings = engine.view().postings().size();
        Outcome o = engine.submit(bad);
        assertEquals(Outcome.Status.REJECTED, o.status(), bad.sourceId());
        assertEquals(code, o.errorCode(), bad.sourceId());
        assertEquals(ledger, now(engine, bad.accountId(), day));
        assertEquals(holds, holdsNow(engine, A, day));
        assertEquals(postings, engine.view().postings().size());
    }

    @Test
    void aSecondSettlementOfTheSameAuthorizationIsRejected() {
        LedgerEngine engine = Support.fixtureThrough("E6");
        assertRejectedWithoutChange(engine, SourceEvent.settlement("S2", 4, A, "Auth-A", "AED", "10.00", 4),
                ErrorCode.AUTHORIZATION_ALREADY_SETTLED);
    }

    @Test
    void captureAboveTheHoldIsRejectedButExactlyTheHoldIsAllowed() {
        LedgerEngine engine = Support.fundedWith("500.00");
        engine.submit(SourceEvent.authorization("H1", 1, A, "X", "AED", "100.00", 1));
        assertRejectedWithoutChange(engine, SourceEvent.settlement("S1", 1, A, "X", "AED", "100.01", 1),
                ErrorCode.CAPTURE_EXCEEDS_HOLD);
        assertEquals(aed("100.00"), holdsNow(engine, A, 1), "the hold survives a rejected capture");
        assertEquals(Outcome.Status.ACCEPTED,
                engine.submit(SourceEvent.settlement("S2", 1, A, "X", "AED", "100.00", 1)).status());
        assertEquals(aed("400.00"), now(engine, A, 1));
    }

    @Test
    void wrongAccountWrongCurrencyDeclinedOrMissingAuthorizationIsRejected() {
        LedgerEngine engine = Support.engineWithExtraAccount();
        engine.submit(SourceEvent.credit("F1", 1, A, "AED", "100.00", 1));
        engine.submit(SourceEvent.credit("F2", 1, "ACC-003", "AED", "100.00", 1));
        engine.submit(SourceEvent.authorization("H1", 1, A, "X", "AED", "50.00", 1));
        engine.submit(SourceEvent.authorization("H2", 1, A, "D", "AED", "999.00", 1)); // declined
        assertRejectedWithoutChange(engine, SourceEvent.settlement("S1", 1, "ACC-003", "X", "AED", "10.00", 1),
                ErrorCode.AUTHORIZATION_ACCOUNT_MISMATCH);
        assertRejectedWithoutChange(engine, SourceEvent.settlement("S2", 1, A, "X", "BHD", "10.000", 1),
                ErrorCode.CURRENCY_MISMATCH);
        assertRejectedWithoutChange(engine, SourceEvent.settlement("S3", 1, A, "D", "AED", "10.00", 1),
                ErrorCode.AUTHORIZATION_DECLINED);
        assertRejectedWithoutChange(engine, SourceEvent.settlement("S4", 1, A, null, "AED", "10.00", 1),
                ErrorCode.MISSING_AUTHORIZATION_ID);
        assertEquals(aed("50.00"), holdsNow(engine, A, 1));
    }

    @Test
    void captureIsNotSubjectToTheNewAuthorizationRule() {
        LedgerEngine engine = Support.fundedWith("10.00");
        engine.submit(SourceEvent.authorization("H1", 1, A, "X", "AED", "10.00", 1));
        engine.submit(SourceEvent.debit("D1", 1, A, "AED", "50.00", 1));
        Outcome o = engine.submit(SourceEvent.settlement("S1", 1, A, "X", "AED", "10.00", 1));
        assertEquals(Outcome.Status.ACCEPTED, o.status(), "an approved hold is honoured even if the ledger fell since");
        assertEquals(aed("-50.00"), now(engine, A, 1));
    }
}
