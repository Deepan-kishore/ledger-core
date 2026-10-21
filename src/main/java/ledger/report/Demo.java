package ledger.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ledger.engine.InterestCalculator;
import ledger.engine.LedgerEngine;
import ledger.engine.LedgerView;
import ledger.input.Account;
import ledger.input.Fixture;
import ledger.input.SourceEvent;
import ledger.money.Money;
import ledger.record.AuthorizationFact;
import ledger.record.DayClose;
import ledger.record.FeePolicyError;
import ledger.record.InterestReport;
import ledger.record.LedgerRecord;
import ledger.record.Outcome;
import ledger.record.Posting;
import ledger.record.PostingType;

/**
 * Console replay. Runs the real engine over the exact fixture and prints what it actually
 * recorded. Nothing printed here is a hard-coded expected value: every number comes from the engine.
 * All state lives in memory and is gone when the program exits.
 *
 * <p>Default output is exactly what the brief asks for, per day: closing ledger balance, fee assessments,
 * authorization states and errors, followed by the final balances. Two optional flags add more:
 * <ul>
 *   <li>{@code --detail}: per day, the restated balance, available balance and interest accrual; then Day 2
 *       at named knowledge cutoffs and the interest calculation.</li>
 *   <li>{@code --trace}: every record the engine appended, event by event.</li>
 * </ul>
 */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) {
        LedgerEngine engine = new LedgerEngine(Fixture.accounts(), LedgerEngine.standardFeeTariffs());
        List<String> trace = new ArrayList<>();
        long seen = 0;
        for (SourceEvent e : Fixture.events()) {
            trace.add("");
            trace.add(">> " + describe(e));
            engine.submit(e);
            seen = collectNewRecords(engine, seen, trace);
        }
        trace.add("");
        trace.add(">> END OF INPUT: close Day6, then capitalize interest once per account");
        engine.finish();
        seen = collectNewRecords(engine, seen, trace);
        engine.finish(); // second call must be a no-op
        trace.add("   (finish() called a second time: " + (engine.lastSequence() - seen) + " new records)");

        boolean detail = List.of(args).contains("--detail");
        boolean traced = List.of(args).contains("--trace");

        line("IN-MEMORY LEDGER REPLAY (state lives in RAM only; every run starts from the openings)");
        for (Account a : engine.accounts()) {
            System.out.println("  account " + a.id() + " " + a.currency() + " opening " + a.opening().plain()
                    + " (scale " + a.currency().scale() + ")");
        }
        if (detail) {
            System.out.println("  overdraft tariff: " + LedgerEngine.standardFeeTariffs()
                    + "; interest " + InterestCalculator.DAILY_RATE.toPlainString() + " per day on positive closes");
        }

        line("DAILY REPORT (one block per day, both accounts)");
        System.out.println("  closing ledger balance = as known when the day closed (never edited afterwards)");
        System.out.println("  fee effective day      = the day the fee belongs to; discovered = the day it was booked");
        if (detail) {
            System.out.println("  restated               = the same day recomputed once all input is known");
        }
        for (String row : dailyReport(engine, detail)) {
            System.out.println(row);
        }

        LedgerView view = engine.view();
        if (detail) {
            printCheckpoints(engine, view);
            printInterest(view);
        }
        printFinal(engine, view);

        if (traced) {
            line("EVENT-BY-EVENT TRACE (every record the engine appended, in order)");
            for (String row : trace) {
                System.out.println(row);
            }
        }
        if (!detail || !traced) {
            System.out.println("  (more: --detail for restated balances and interest, --trace for every record)");
        }
    }

    // ------------------------------------------------------------------ daily report

    /**
     * For each day and each account: closing ledger balance as known at that day's close, overdraft fees,
     * authorization states as known at the close, and errors recorded that day. Empty sections print
     * "none". With {@code detail}, also the restated balance, available balance and interest accrual.
     */
    static List<String> dailyReport(LedgerEngine engine, boolean detail) {
        LedgerView view = engine.view();
        long restatedCutoff = engine.checkpoint("window:before-capitalization");
        Map<String, String> accountOfSource = new HashMap<>();
        for (SourceEvent e : engine.inputLog()) {
            accountOfSource.putIfAbsent(e.sourceId(), e.accountId());
        }

        List<String> out = new ArrayList<>();
        for (int d = LedgerEngine.FIRST_DAY; d <= LedgerEngine.LAST_DAY; d++) {
            out.add("");
            out.add("Day " + d);
            for (Account a : engine.accounts()) {
                String id = a.id();
                DayClose close = originalClose(view, id, d);
                Money restated = view.balance(id, d, restatedCutoff);
                boolean lastDay = d == LedgerEngine.LAST_DAY;
                out.add("  " + id + " (" + a.currency() + ")");

                out.add(row("closing ledger balance", close.ledger().plain()
                        + (lastDay ? " (after interest capitalization)" : "")));
                if (detail) {
                    if (lastDay) {
                        out.add(row("before capitalization", restated.plain()));
                    } else {
                        out.add(row("restated", restated.plain() + (restated.compareTo(close.ledger()) == 0
                                ? " (unchanged)" : " (changed by events that arrived later)")));
                    }
                    out.add(row("available balance", close.available().plain()
                            + " (ledger minus active holds " + close.activeHolds().plain() + ")"));
                }

                List<String> fees = new ArrayList<>();
                for (Posting p : view.postings(id, PostingType.OVERDRAFT_FEE)) {
                    if (p.valueDay() == d) {
                        fees.add(p.amount().negate() + " effective Day" + p.valueDay() + ", discovered Day"
                                + p.recordedDay() + " (" + p.cause() + ")");
                    }
                }
                addAll(out, "overdraft fee", fees);
                List<String> discovered = new ArrayList<>();
                for (Posting p : view.postings(id, PostingType.OVERDRAFT_FEE)) {
                    if (p.recordedDay() == d && p.valueDay() < d) {
                        discovered.add(p.amount().negate() + " for Day" + p.valueDay() + " (" + p.cause() + ")");
                    }
                }
                if (!discovered.isEmpty()) {
                    addAll(out, "earlier-day fees booked", discovered);
                }

                addAll(out, "authorizations", authorizationStates(view, id, d, close.knowledgeCutoff()));

                List<String> errors = new ArrayList<>();
                for (Outcome o : view.recordsOf(Outcome.class)) {
                    if (o.status() != Outcome.Status.ACCEPTED && o.recordedDay() == d
                            && id.equals(accountOfSource.get(o.sourceId()))) {
                        errors.add(o.sourceId() + " " + o.status() + " " + o.errorCode() + ": " + o.message());
                    }
                }
                for (FeePolicyError f : view.recordsOf(FeePolicyError.class)) {
                    if (f.recordedDay() == d && f.accountId().equals(id)) {
                        errors.add(f.code() + " for Day" + f.effectiveDay() + " (close " + f.closingBalance() + ")");
                    }
                }
                addAll(out, "errors", errors);

                for (InterestReport r : view.recordsOf(InterestReport.class)) {
                    if (detail && r.accountId().equals(id)) {
                        InterestReport.DailyAccrual accrual = r.days().get(d - 1);
                        out.add(row("interest accrual", accrual.rounded().plain() + " (on restated balance "
                                + accrual.basis().plain() + ")"));
                        if (lastDay) {
                            out.add(row("interest capitalized", r.capitalized().plain()
                                    + " (sum of the six daily accruals)"));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Every authorization on the account that exists by the cutoff, with its state at that point. */
    private static List<String> authorizationStates(LedgerView view, String accountId, int day, long cutoff) {
        Set<String> ids = new LinkedHashSet<>();
        for (AuthorizationFact f : view.recordsOf(AuthorizationFact.class)) {
            if (f.accountId().equals(accountId) && f.sequence() <= cutoff) {
                ids.add(f.authorizationId());
            }
        }
        List<String> states = new ArrayList<>();
        for (String authId : ids) {
            AuthorizationFact f = view.latestAuthorizationFact(authId, cutoff).orElseThrow();
            String state = switch (f.kind()) {
                case APPROVED -> "APPROVED, hold " + f.hold().plain() + " active";
                case DECLINED -> "DECLINED, no hold created";
                case SETTLED -> "SETTLED, captured " + f.captured().plain() + ", hold " + f.hold().plain() + " released";
            };
            states.add(authId + " " + state + (f.recordedDay() == day ? " (changed today)" : ""));
        }
        return states;
    }

    private static String row(String label, String value) {
        return String.format("    %-24s: %s", label, value);
    }

    private static void addAll(List<String> out, String label, List<String> values) {
        if (values.isEmpty()) {
            out.add(row(label, "none"));
        }
        for (String v : values) {
            out.add(row(label, v));
        }
    }

    // ------------------------------------------------------------------ summary sections

    private static void printCheckpoints(LedgerEngine engine, LedgerView view) {
        line("ACC-001 DAY2 BALANCE AT NAMED KNOWLEDGE CUTOFFS");
        String[][] points = {
                {"close:Day2", "original Day2 close"},
                {"before:E7", "Day5 knowledge (Day4 closed), before E7"},
                {"principal:E7", "after E7 debit, before re-assessment fees"},
                {"after:E7", "after E7 and its re-assessment fees"},
                {"after:E9", "after E9 reversal (fees retained)"},
        };
        for (String[] p : points) {
            long cutoff = engine.checkpoint(p[0]);
            System.out.println(String.format("  %-14s #%-4d Day2 ledger %10s   %s", p[0], cutoff,
                    view.balance(Fixture.ACC_AED, 2, cutoff).plain(), p[1]));
        }
    }

    private static void printInterest(LedgerView view) {
        line("INTEREST (each day rounded on its own; the capitalized credit is their sum)");
        for (InterestReport r : view.recordsOf(InterestReport.class)) {
            int scale = r.capitalized().currency().scale();
            System.out.println("  " + r.accountId() + " basis cutoff #" + r.basisCutoff()
                    + " (excludes the capitalization credit)");
            System.out.println(String.format("    %-4s %12s %12s %10s", "day", "basis", "raw", "rounded"));
            for (InterestReport.DailyAccrual d : r.days()) {
                System.out.println(String.format("    D%-3d %12s %12s %10s", d.day(),
                        d.basis().plain(), raw(d.raw(), scale), d.rounded().plain()));
            }
            System.out.println("    sum of rounded daily accruals " + r.capitalized().plain()
                    + "; exact raw sum " + raw(r.rawSum(), scale) + " (shown for comparison)");
            List<Posting> caps = view.postings(r.accountId(), PostingType.INTEREST_CAPITALIZATION);
            for (Posting p : caps) {
                System.out.println("    capitalization credit " + p.amount() + " value Day" + p.valueDay()
                        + " key " + p.key());
            }
            if (caps.isEmpty()) {
                System.out.println("    no capitalization credit (total is zero)");
            }
        }
    }

    private static void printFinal(LedgerEngine engine, LedgerView view) {
        line("FINAL BALANCES (each currency on its own; never totalled together)");
        for (Account a : engine.accounts()) {
            System.out.println("  " + a.id() + " ledger " + view.balance(a.id(), LedgerEngine.LAST_DAY,
                    engine.lastSequence()) + ", available " + view.available(a.id(), LedgerEngine.LAST_DAY,
                    engine.lastSequence()));
        }
        System.out.println("  records in log: " + engine.lastSequence() + "; replay finished normally");
    }

    // ------------------------------------------------------------------ formatting helpers

    private static long collectNewRecords(LedgerEngine engine, long seen, List<String> trace) {
        List<LedgerRecord> all = engine.records();
        for (LedgerRecord r : all.subList((int) seen, all.size())) {
            trace.add("   " + format(r));
        }
        return all.size();
    }

    static String format(LedgerRecord r) {
        String head = String.format("#%-3d rec Day%d  ", r.sequence(), r.recordedDay());
        if (r instanceof Posting p) {
            return head + "POSTING " + p.accountId() + " " + p.type() + " " + p.amount().plain() + " "
                    + p.amount().currency() + " value Day" + p.valueDay()
                    + (p.key() != null ? " key " + p.key() : "")
                    + (p.reversesSequence() != null ? " reverses #" + p.reversesSequence() : "")
                    + " [" + p.cause() + "]";
        }
        if (r instanceof AuthorizationFact f) {
            return head + "AUTH " + f.authorizationId() + " " + f.kind() + " hold " + f.hold().plain()
                    + (f.ledgerAtDecision() != null ? " (decision ledger " + f.ledgerAtDecision().plain()
                    + ", active holds " + f.activeHoldsAtDecision().plain() + ")" : "")
                    + (f.captured() != null ? " captured " + f.captured().plain() + ", released " + f.hold().plain() : "");
        }
        if (r instanceof Outcome o) {
            return head + "OUTCOME " + o.sourceId() + " " + o.status()
                    + (o.errorCode() != null ? " " + o.errorCode() : "") + ": " + o.message()
                    + " effects " + o.effectSequences();
        }
        if (r instanceof DayClose c) {
            return head + "ORIGINAL CLOSE " + c.accountId() + " Day" + c.day() + " ledger " + c.ledger().plain()
                    + " holds " + c.activeHolds().plain() + " available " + c.available().plain()
                    + " [knowledge cutoff #" + c.knowledgeCutoff() + "]"
                    + (c.postCapitalization() ? " POST-CAPITALIZATION" : "");
        }
        if (r instanceof FeePolicyError f) {
            return head + "FEE POLICY ERROR " + f.accountId() + " Day" + f.effectiveDay() + " " + f.code()
                    + " close " + f.closingBalance() + " (no fee posted)";
        }
        if (r instanceof InterestReport i) {
            return head + "INTEREST REPORT " + i.accountId() + " raw sum "
                    + raw(i.rawSum(), i.capitalized().currency().scale())
                    + " capitalized (sum of rounded days) " + i.capitalized();
        }
        throw new IllegalStateException("Unknown record " + r);
    }

    private static String describe(SourceEvent e) {
        StringBuilder sb = new StringBuilder(e.sourceId() + " booked Day" + e.bookedDay() + " " + e.kind()
                + " " + e.accountId());
        if (e.authorizationId() != null) {
            sb.append(" ").append(e.authorizationId());
        }
        if (e.amountText() != null) {
            sb.append(" ").append(e.currencyText()).append(" ").append(e.amountText());
        }
        if (e.targetSourceId() != null) {
            sb.append(" target ").append(e.targetSourceId());
        }
        sb.append(" value Day").append(e.valueDay());
        if (e.instalmentCount() > 0) {
            sb.append(" in ").append(e.instalmentCount()).append(" instalments");
        }
        return sb.toString();
    }

    private static DayClose originalClose(LedgerView view, String accountId, int day) {
        for (DayClose c : view.recordsOf(DayClose.class)) {
            if (c.accountId().equals(accountId) && c.day() == day) {
                return c;
            }
        }
        throw new IllegalStateException("No original close for " + accountId + " Day" + day);
    }

    /** Shows a raw accrual with at least two more decimals than the currency, trailing zeros trimmed. */
    private static String raw(BigDecimal value, int currencyScale) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() < currencyScale + 2) {
            v = v.setScale(currencyScale + 2);
        }
        return v.toPlainString();
    }

    private static void line(String title) {
        System.out.println();
        System.out.println("==== " + title + " ====");
    }
}
