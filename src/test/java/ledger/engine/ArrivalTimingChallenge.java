package ledger.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import ledger.input.Fixture;
import ledger.input.SourceEvent;
import ledger.money.Money;
import ledger.record.Outcome;
import ledger.record.Posting;
import ledger.record.PostingType;
import org.junit.jupiter.api.Test;

/**
 * INTENTIONALLY FAILING test against this design. It is not part of the normal suite.
 *
 * <p>The class name does not match Surefire's default includes (Test*, *Test, *Tests, *TestCase), so
 * {@code mvn test} never runs it. Run it on its own:
 * <pre>mvn -Dtest=ArrivalTimingChallenge test</pre>
 * Expected: 1 test run, 1 failure, 0 errors, exit code 1, message "expected: &lt;AED 50.00&gt; but was:
 * &lt;AED 75.00&gt;".
 *
 * <p><b>The real-world scenario.</b> The bank wrongly debits AED 620.00 (E7) and then corrects it with a
 * reversal (E9). Whether that correction reaches the ledger late on Day 5 or early on Day 6 is decided by
 * the bank's own processing, for example a batch cutoff, not by anything the customer does. A customer
 * would expect to pay the same overdraft fees either way, because the facts are the same: the same debit,
 * the same correction, the same value dates.
 *
 * <p><b>What it reveals.</b> In this design the customer pays AED 75.00 if the correction arrives on Day 6
 * and AED 50.00 if it arrives on Day 5. A fee is charged as soon as a day is known to be negative (at its
 * close, or when a backdated entry re-checks it), and once charged it is never taken back:
 * <ul>
 *   <li>Day 2 and Day 4 fees: charged the moment E7 posts, in both runs.</li>
 *   <li>Day 5: with E9 on Day 6, Day 5 has already closed at -205.00 and is charged; with E9 on Day 5,
 *       E9 lands first, Day 5 closes at +415.00 and is not charged.</li>
 * </ul>
 * So the charge depends on <em>when</em> a correction arrives, not only on <em>what</em> it corrects.
 *
 * <p><b>Why the design is like this, and what fixing it costs.</b> The cause is the fee rule, not the
 * frozen day snapshots: the brief says when to charge a fee but gives no rule for refunding one, so the
 * engine never refunds. A fix would add that rule: whenever a correction makes a charged day positive
 * again, append a +25.00 refund posting (the original fee and the day's snapshot stay as they are). Both
 * runs would then end at AED 0.00 in net fees. The cost is a business rule the brief does not give (refund
 * on any correction? only full reversals? only within the window?), and customers seeing a fee charged and
 * later refunded. The engine does not invent that rule.
 *
 * <p>The first assertions check that both runs are valid (every event accepted or declined exactly as in
 * the fixture, and the same money moved apart from fees), so if this test fails anywhere other than the
 * last line, the setup is wrong, not the design.
 */
class ArrivalTimingChallenge {

    private static LedgerEngine replay(List<SourceEvent> events) {
        LedgerEngine engine = new LedgerEngine(Fixture.accounts(), LedgerEngine.standardFeeTariffs());
        events.forEach(engine::submit);
        engine.finish();
        return engine;
    }

    /** All ACC-001 money movements except fees and interest: what the customer and the bank actually did. */
    private static Money principal(LedgerEngine engine) {
        Money total = Money.zero(Fixture.accounts().get(0).currency());
        for (Posting p : engine.view().postings()) {
            if (p.accountId().equals(Fixture.ACC_AED) && p.type() != PostingType.OVERDRAFT_FEE
                    && p.type() != PostingType.INTEREST_CAPITALIZATION) {
                total = total.plus(p.amount());
            }
        }
        return total;
    }

    @Test
    void feesShouldNotDependOnWhichDayTheCorrectionArrives() {
        // Run 1: the fixture exactly as supplied. E9 (the correction) arrives on Day 6.
        LedgerEngine correctionOnDay6 = replay(Fixture.events());

        // Run 2: identical, except E9 arrives on Day 5, right after E8. Same amount, same target, same value day.
        List<SourceEvent> earlier = new ArrayList<>();
        for (SourceEvent e : Fixture.events()) {
            earlier.add(e.sourceId().equals("E9")
                    ? SourceEvent.reversal("E9", 5, e.accountId(), e.targetSourceId(), e.valueDay())
                    : e);
        }
        LedgerEngine correctionOnDay5 = replay(earlier);

        // Preconditions: both runs are valid and describe the same facts.
        for (LedgerEngine run : List.of(correctionOnDay6, correctionOnDay5)) {
            assertEquals(Outcome.Status.ACCEPTED, run.outcome("E9").orElseThrow().status(), "E9 is accepted");
            assertEquals(Outcome.Status.REJECTED, run.outcome("E6").orElseThrow().status(), "E6 is rejected");
            assertEquals(Outcome.Status.DECLINED, run.outcome("E8").orElseThrow().status(), "E8 is declined");
        }
        assertEquals(principal(correctionOnDay6), principal(correctionOnDay5),
                "the same money moved in both runs (E7 fully reversed); only the timing differs");

        Money feesWhenCorrectedOnDay6 = correctionOnDay6.view().total(Fixture.ACC_AED, PostingType.OVERDRAFT_FEE).negate();
        Money feesWhenCorrectedOnDay5 = correctionOnDay5.view().total(Fixture.ACC_AED, PostingType.OVERDRAFT_FEE).negate();

        // The single assertion about the design. It fails: AED 75.00 (Days 2, 4, 5) with the correction on
        // Day 6, against AED 50.00 (Days 2, 4) with the correction on Day 5.
        assertEquals(feesWhenCorrectedOnDay5, feesWhenCorrectedOnDay6,
                "Same debit, same correction, same value dates: overdraft fees for ACC-001 should not depend on "
                        + "whether the correction arrives on Day 5 (expected) or Day 6 (actual)");
    }
}
