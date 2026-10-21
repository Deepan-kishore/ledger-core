# bench

`ScaleProbe.java` measures how replay time grows with volume. It is not part of the Maven build or the test
suite. The architecture document's section 1 quotes these numbers.

## What it measures

Volume multiplier *k* = *k* copies of the fixture's ten events, each copy on its own pair of accounts,
interleaved event by event so the booked days keep the fixture's order (E10 still arrives after E9). For each
*k*: one full replay plus `finish()`, `finish()` alone, and one extra authorization submitted after the
replay. The best of three runs is reported, after a JIT warm-up.

## Run it

```bash
mvn -q package -DskipTests
javac -cp target/classes -d target/bench bench/ScaleProbe.java
java -Xmx6g -Dreps=3 -cp target/classes:target/bench ScaleProbe 100 1000 3000
```

Expect about a minute for 3,000 copies (30,000 events) on the current code.

## Results

Measured against commit `1a02069` on an Apple M4, JDK 25, `-Xmx6g`, best of 3. Raw rows: `results.csv`.

| Events | Records | Current code | No defensive copy | Per-account index | + authorization and original-posting index |
|---|---:|---:|---:|---:|---:|
| 1,000 (100×) | 4,102 | 56 ms | | 6 ms | 5 ms |
| 10,000 | 41,002 | 5.1 s | 4.8 s | 0.20 s | 0.03 s |
| 30,000 | 123,002 | 57 s | 55 s | 1.7 s | 0.07 s |
| 100,000 | 410,002 | not run | | 41 s | 0.25 s |

The variants:
- **Current code**: `main` as committed.
- **No defensive copy**: a throwaway one-line change, not committed: `LedgerView` keeps the list it is given
  instead of copying it. Saves about 4%, so the copy is not the problem; the scans are.
- **Per-account index**: postings and authorization facts are also kept in a list per account, so a balance
  scans one account's records. Still quadratic: looking up an authorization by id still scans the whole log.
- **+ authorization and original-posting index**: the latest fact per authorization id and the original
  posting per source id are also kept. Linear. This version is on the branch `perf/index-on-write`; the 84
  tests pass and the replay output is byte-identical to `main`.
