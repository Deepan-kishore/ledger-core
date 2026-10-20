package ledger.engine;

import static ledger.engine.Support.B;
import static ledger.engine.Support.bhd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import ledger.input.SourceEvent;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import ledger.record.DayClose;
import ledger.record.ErrorCode;
import ledger.record.Outcome;
import ledger.record.Posting;
import ledger.record.PostingType;
import org.junit.jupiter.api.Test;

/** E10: BHD 10.000 split exactly into three children (criterion 7), arriving late after E9. */
class InstalmentTest {

    @Test
    void tenDinarsSplitAs3334_3333_3333() {
        LedgerEngine engine = Support.fixtureThrough("E10");
        List<Posting> children = Support.postingsFor(engine, "E10");
        assertEquals(List.of(BigInteger.valueOf(3334), BigInteger.valueOf(3333), BigInteger.valueOf(3333)),
                children.stream().map(p -> p.amount().minorUnits()).toList());
        assertEquals(List.of("E10#1", "E10#2", "E10#3"), children.stream().map(Posting::key).toList(),
                "the leftover fil goes to the first child");
        assertTrue(children.stream().allMatch(p -> p.type() == PostingType.INSTALMENT_CREDIT));
        Money sum = Money.zero(CurrencyCode.BHD);
        for (Posting p : children) {
            sum = sum.plus(p.amount());
        }
        assertEquals(bhd("10.000"), sum, "exactly the instructed total");
        // Criterion 7 ("each must be 3.334") would credit 10.002: refused.
        assertEquals(bhd("10.002"), bhd("3.334").plus(bhd("3.334")).plus(bhd("3.334")));
    }

    @Test
    void lateE10IsProcessedOnDay6ButCountsForDay5() {
        LedgerEngine engine = Support.fixtureThrough("E10");
        Outcome e9 = engine.outcome("E9").orElseThrow();
        Outcome e10 = engine.outcome("E10").orElseThrow();
        assertTrue(e10.sequence() > e9.sequence(), "replayed in the given order: E10 after E9");
        List<Posting> children = Support.postingsFor(engine, "E10");
        assertTrue(children.stream().allMatch(p -> p.recordedDay() == 6), "the clock never goes back to Day 5");
        assertTrue(children.stream().allMatch(p -> p.valueDay() == 5), "but the money counts for Day 5");
        assertEquals(3, children.size(), "three children; the parent itself is not a posting");

        DayClose originalDay5 = engine.view().recordsOf(DayClose.class).stream()
                .filter(c -> c.accountId().equals(B) && c.day() == 5).findFirst().orElseThrow();
        assertEquals(bhd("0.000"), originalDay5.ledger(), "Day 5 had already closed when E10 arrived");
        assertEquals(bhd("10.000"), Support.now(engine, B, 5), "Day 5 restated with everything known");
    }

    @Test
    void otherSplitsAreDeterministic() {
        LedgerEngine engine = Support.engine();
        engine.submit(SourceEvent.instalmentCredit("I1", 1, B, "BHD", "1.000", 1, 3));
        engine.submit(SourceEvent.instalmentCredit("I2", 1, B, "BHD", "0.005", 1, 4));
        engine.submit(SourceEvent.instalmentCredit("I3", 1, B, "BHD", "10.000", 1, 4));
        engine.submit(SourceEvent.instalmentCredit("I4", 1, B, "BHD", "5.000", 1, 1));
        assertEquals(List.of("0.334", "0.333", "0.333"),
                Support.postingsFor(engine, "I1").stream().map(p -> p.amount().plain()).toList());
        assertEquals(List.of("0.002", "0.001", "0.001", "0.001"),
                Support.postingsFor(engine, "I2").stream().map(p -> p.amount().plain()).toList());
        assertEquals(List.of("2.500", "2.500", "2.500", "2.500"),
                Support.postingsFor(engine, "I3").stream().map(p -> p.amount().plain()).toList());
        assertEquals(List.of("5.000"),
                Support.postingsFor(engine, "I4").stream().map(p -> p.amount().plain()).toList());
    }

    @Test
    void invalidSplitsPostNoChildren() {
        LedgerEngine engine = Support.engine();
        assertEquals(ErrorCode.INVALID_INSTALMENT_COUNT,
                engine.submit(SourceEvent.instalmentCredit("I1", 1, B, "BHD", "10.000", 1, 0)).errorCode());
        assertEquals(ErrorCode.INVALID_INSTALMENT_COUNT,
                engine.submit(SourceEvent.instalmentCredit("I2", 1, B, "BHD", "10.000", 1, -3)).errorCode());
        assertEquals(ErrorCode.INVALID_INSTALMENT_COUNT,
                engine.submit(SourceEvent.instalmentCredit("I3", 1, B, "BHD", "0.002", 1, 3)).errorCode(),
                "a child would be zero");
        assertEquals(ErrorCode.NEGATIVE_AMOUNT,
                engine.submit(SourceEvent.instalmentCredit("I4", 1, B, "BHD", "-10.000", 1, 3)).errorCode());
        assertEquals(ErrorCode.EXCESS_PRECISION,
                engine.submit(SourceEvent.instalmentCredit("I5", 1, B, "BHD", "10.0001", 1, 3)).errorCode());
        assertTrue(engine.view().postings().isEmpty(), "all or nothing: no partial children");
    }

    @Test
    void theWholeFixtureRunsToTheEnd() {
        LedgerEngine engine = Support.fullFixture();
        assertTrue(engine.isFinalized());
        assertEquals(Support.aed("390.00"), Support.now(engine, Support.A, 6));
        assertEquals(bhd("10.000"), Support.now(engine, B, 6));
        assertTrue(engine.view().postings(Support.A, PostingType.OVERDRAFT_FEE).stream()
                .noneMatch(f -> f.valueDay() == 6), "Day 6 closes positive");
    }
}
