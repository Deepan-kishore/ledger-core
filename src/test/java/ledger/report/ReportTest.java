package ledger.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import ledger.engine.LedgerEngine;
import ledger.input.Fixture;
import ledger.input.SourceEvent;
import org.junit.jupiter.api.Test;

/** The per-day output the brief asks for: closing balance, fees, authorization states and errors. */
class ReportTest {

    private static final String A = Fixture.ACC_AED;
    private static final String B = Fixture.ACC_BHD;

    private static List<String> report() {
        LedgerEngine engine = new LedgerEngine(Fixture.accounts(), LedgerEngine.standardFeeTariffs());
        for (SourceEvent e : Fixture.events()) {
            engine.submit(e);
        }
        engine.finish();
        return Demo.dailyReport(engine);
    }

    /** Lines of one account's block on one day of the daily report. */
    private static List<String> block(List<String> report, int day, String account) {
        int start = report.indexOf("Day " + day);
        int end = day < LedgerEngine.LAST_DAY ? report.indexOf("Day " + (day + 1)) : report.size();
        List<String> dayLines = report.subList(start, end);
        int from = dayLines.indexOf("  " + account + (account.equals(A) ? " (AED)" : " (BHD)"));
        int to = from + 1;
        while (to < dayLines.size() && dayLines.get(to).startsWith("    ")) {
            to++;
        }
        return dayLines.subList(from, to);
    }

    private static boolean has(List<String> lines, String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }

    private static String runDemo(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            Demo.main(args);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void everyDayShowsBothAccountsWithEveryRequiredSection() {
        List<String> report = report();
        for (int d = 1; d <= 6; d++) {
            for (String account : List.of(A, B)) {
                List<String> b = block(report, d, account);
                for (String section : List.of("closing ledger balance", "overdraft fee", "authorizations", "errors")) {
                    assertTrue(has(b, section), "Day" + d + " " + account + " missing " + section);
                }
            }
        }
        assertTrue(has(block(report, 1, A), "errors                  : none"), "empty sections say 'none'");
    }

    @Test
    void balancesShowWhatWasKnownThenAndWhatIsKnownNow() {
        List<String> report = report();
        List<String> d2 = block(report, 2, A);
        assertTrue(has(d2, "closing ledger balance  : 250.00"));
        assertTrue(has(d2, "restated                : 225.00 (changed by events that arrived later)"));
        assertTrue(has(block(report, 5, A), "closing ledger balance  : -230.00"));
        assertTrue(has(block(report, 5, B), "restated                : 10.000 (changed by events that arrived later)"),
                "late E10 changes Day 5 for ACC-002");
        assertTrue(has(block(report, 6, A), "closing ledger balance  : 390.93 (after interest capitalization)"));
        assertTrue(has(block(report, 6, A), "interest capitalized    : 0.93"));
    }

    @Test
    void feesShowTheDayTheyBelongToAndTheDayTheyWereFound() {
        List<String> report = report();
        assertTrue(has(block(report, 2, A), "AED 25.00 effective Day2, discovered Day5"));
        assertTrue(has(block(report, 4, A), "AED 25.00 effective Day4, discovered Day5"));
        List<String> d5 = block(report, 5, A);
        assertTrue(has(d5, "AED 25.00 effective Day5, discovered Day5 (close of Day5)"));
        assertTrue(has(d5, "earlier-day fees booked : AED 25.00 for Day2"));
        assertTrue(has(d5, "earlier-day fees booked : AED 25.00 for Day4"));
        assertTrue(has(block(report, 3, A), "overdraft fee           : none"), "Day 3 escapes at +5");
    }

    @Test
    void authorizationStatesAndErrorsAppearOnTheRightDays() {
        List<String> report = report();
        assertTrue(has(block(report, 2, A), "Auth-A APPROVED, hold 200.00 active (changed today)"));
        assertTrue(has(block(report, 3, A), "Auth-A APPROVED, hold 200.00 active"), "still active on Day 3");
        List<String> d4 = block(report, 4, A);
        assertTrue(has(d4, "Auth-A SETTLED, captured 185.00, hold 200.00 released (changed today)"));
        assertTrue(has(d4, "E6 REJECTED UNKNOWN_AUTHORIZATION"));
        List<String> d5 = block(report, 5, A);
        assertTrue(has(d5, "Auth-B DECLINED, no hold created (changed today)"));
        assertTrue(has(d5, "E8 DECLINED INSUFFICIENT_AVAILABLE_FUNDS"));
    }

    @Test
    void consoleOutputRunsTheRealEngine() {
        String out = runDemo();
        for (String label : List.of("DAILY REPORT", "KNOWLEDGE CUTOFFS", "INTEREST",
                "ACC-001 ledger AED 390.93", "ACC-002 ledger BHD 10.008", "replay finished normally")) {
            assertTrue(out.contains(label), "missing: " + label);
        }
        assertFalse(out.contains("EVENT-BY-EVENT TRACE"), "the trace is opt-in");
        assertFalse(out.contains("Exception"));
    }

    @Test
    void traceFlagAddsEveryRecordEventByEvent() {
        String out = runDemo("--trace");
        for (String label : List.of("EVENT-BY-EVENT TRACE", ">> E7 booked Day5 DEBIT",
                "ORIGINAL CLOSE ACC-001 Day5 ledger -230.00", "POST-CAPITALIZATION")) {
            assertTrue(out.contains(label), "missing: " + label);
        }
    }
}
