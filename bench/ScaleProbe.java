import java.util.ArrayList;
import java.util.List;
import ledger.engine.LedgerEngine;
import ledger.input.Account;
import ledger.input.EventKind;
import ledger.input.Fixture;
import ledger.input.SourceEvent;

/**
 * Scale probe for the architecture document (section 1). Not part of the Maven build or the test suite.
 *
 * <p>Volume multiplier k = k copies of the fixture's ten events, each copy on its own pair of accounts,
 * interleaved event by event so booked days keep the fixture's order (E10 still arrives after E9). For each
 * k it times one full replay plus {@code finish()}, {@code finish()} alone, and one extra authorization
 * submitted after the replay. The best of {@code -Dreps} runs (default 3) is printed, after a JIT warm-up.
 *
 * <pre>
 * mvn -q package -DskipTests
 * javac -cp target/classes -d target/bench bench/ScaleProbe.java
 * java -Xmx6g -Dreps=3 -cp target/classes:target/bench ScaleProbe 100 1000 3000
 * </pre>
 */
public class ScaleProbe {

    static SourceEvent copy(SourceEvent e, int k) {
        String s = "-" + k;
        return new SourceEvent(e.sourceId() + s, e.bookedDay(), e.kind(), e.accountId() + s, e.currencyText(),
                e.amountText(), e.valueDay(), e.authorizationId() == null ? null : e.authorizationId() + s,
                e.targetSourceId() == null ? null : e.targetSourceId() + s, e.instalmentCount());
    }

    static List<Account> accounts(int copies) {
        List<Account> out = new ArrayList<>();
        for (int k = 0; k < copies; k++) {
            for (Account a : Fixture.accounts()) {
                out.add(new Account(a.id() + "-" + k, a.currency(), a.opening()));
            }
        }
        return out;
    }

    static List<SourceEvent> events(int copies) {
        List<SourceEvent> out = new ArrayList<>();
        for (SourceEvent e : Fixture.events()) {
            for (int k = 0; k < copies; k++) {
                out.add(copy(e, k));
            }
        }
        return out;
    }

    /** Returns {events, records, replay ns, one authorization ns, finish ns}. */
    static long[] run(int copies) {
        LedgerEngine engine = new LedgerEngine(accounts(copies), LedgerEngine.standardFeeTariffs());
        List<SourceEvent> evs = events(copies);
        long t0 = System.nanoTime();
        for (SourceEvent e : evs) {
            engine.submit(e);
        }
        long t1 = System.nanoTime();
        engine.submit(new SourceEvent("AUTH-PROBE", 6, EventKind.AUTHORIZATION, "ACC-002-0", "BHD", "1.000", 6,
                "Auth-PROBE", null, 0));
        long t2 = System.nanoTime();
        engine.finish();
        long t3 = System.nanoTime();
        return new long[] {evs.size(), engine.lastSequence(), t1 - t0, t2 - t1, t3 - t2};
    }

    public static void main(String[] args) {
        for (int w = 0; w < 3; w++) {
            run(5); // warm up the JIT
        }
        int reps = Integer.parseInt(System.getProperty("reps", "3"));
        System.out.println("copies,events,records,replay_ms,one_auth_ms,finish_ms,total_ms");
        for (String a : args) {
            int copies = Integer.parseInt(a);
            long[] best = null;
            for (int r = 0; r < reps; r++) {
                long[] x = run(copies);
                if (best == null || x[2] + x[4] < best[2] + best[4]) {
                    best = x;
                }
            }
            System.out.printf("%d,%d,%d,%.1f,%.2f,%.1f,%.1f%n", copies, best[0], best[1], best[2] / 1e6,
                    best[3] / 1e6, best[4] / 1e6, (best[2] + best[4]) / 1e6);
        }
    }
}
