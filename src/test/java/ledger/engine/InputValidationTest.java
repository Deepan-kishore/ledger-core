package ledger.engine;

import static ledger.input.Fixture.ACC_AED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import ledger.input.Fixture;
import ledger.input.SourceEvent;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import ledger.record.ErrorCode;
import ledger.record.Outcome;
import org.junit.jupiter.api.Test;

/** Bad input is recorded as a REJECTED outcome and the rejected event itself posts nothing. */
class InputValidationTest {

    private static LedgerEngine engine() {
        return new LedgerEngine(Fixture.accounts(), LedgerEngine.standardFeeTariffs());
    }

    @Test
    void invalidEventsAreRejectedWithoutAnyPosting() {
        LedgerEngine engine = engine();
        Object[][] cases = {
                {SourceEvent.credit("X1", 1, ACC_AED, "AED", "1.005", 1), ErrorCode.EXCESS_PRECISION},
                {SourceEvent.credit("X2", 1, ACC_AED, "AED", "abc", 1), ErrorCode.INVALID_AMOUNT},
                {SourceEvent.credit("X3", 1, ACC_AED, "AED", "-5.00", 1), ErrorCode.NEGATIVE_AMOUNT},
                {SourceEvent.credit("X4", 1, ACC_AED, "AED", "0.00", 1), ErrorCode.ZERO_AMOUNT},
                {SourceEvent.credit("X5", 1, ACC_AED, "USD", "5.00", 1), ErrorCode.UNSUPPORTED_CURRENCY},
                {SourceEvent.credit("X6", 1, ACC_AED, "BHD", "5.000", 1), ErrorCode.CURRENCY_MISMATCH},
                {SourceEvent.credit("X7", 1, "ACC-999", "AED", "5.00", 1), ErrorCode.UNKNOWN_ACCOUNT},
                {SourceEvent.credit("X8", 1, ACC_AED, "AED", "5.00", 2), ErrorCode.INVALID_VALUE_DAY},
                {SourceEvent.credit("X9", 7, ACC_AED, "AED", "5.00", 1), ErrorCode.INVALID_BOOKED_DAY},
                {SourceEvent.credit("X10", 1, ACC_AED, "AED", null, 1), ErrorCode.INVALID_AMOUNT},
        };
        for (Object[] c : cases) {
            Outcome o = engine.submit((SourceEvent) c[0]);
            assertEquals(Outcome.Status.REJECTED, o.status(), o.sourceId());
            assertEquals(c[1], o.errorCode(), o.sourceId());
            assertTrue(o.effectSequences().isEmpty(), o.sourceId());
        }
        assertTrue(engine.view().postings().isEmpty(), "no money moved for any rejected event");
        assertEquals(Money.zero(CurrencyCode.AED), engine.view().balance(ACC_AED, 1, engine.lastSequence()));
    }

    @Test
    void blankSourceIdIsRejectedWithoutMovingMoneyOrTheClock() {
        LedgerEngine engine = engine();
        for (String blank : List.of("", "   ")) {
            Outcome o = engine.submit(SourceEvent.credit(blank, 3, ACC_AED, "AED", "1.00", 3));
            assertEquals(Outcome.Status.REJECTED, o.status());
            assertEquals(ErrorCode.MISSING_SOURCE_ID, o.errorCode());
        }
        assertTrue(engine.view().postings().isEmpty());
        assertEquals(1, engine.watermark(), "an unidentifiable event does not move the clock");
    }

    @Test
    void identicalResubmissionReturnsTheOriginalOutcomeAndChangesNothing() {
        LedgerEngine engine = engine();
        SourceEvent e1 = SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1);
        Outcome first = engine.submit(e1);
        long size = engine.lastSequence();
        assertSame(first, engine.submit(e1));
        assertEquals(size, engine.lastSequence(), "no new records");
        assertEquals(2, engine.inputLog().size(), "the resubmission is still in the input log");
    }

    @Test
    void reusingAnIdForADifferentEventIsRejectedAndTheOriginalKept() {
        LedgerEngine engine = engine();
        engine.submit(SourceEvent.credit("E1", 1, ACC_AED, "AED", "1200.00", 1));
        Outcome conflict = engine.submit(SourceEvent.credit("E1", 1, ACC_AED, "AED", "5.00", 1));
        assertEquals(ErrorCode.DUPLICATE_SOURCE_CONFLICT, conflict.errorCode());
        assertEquals(Outcome.Status.ACCEPTED, engine.outcome("E1").orElseThrow().status(), "original kept");
        assertEquals(1, engine.view().postings().size());
    }
}
