# REJECTED

Acceptance criteria refused (with reasons), approaches abandoned during the build, and approaches
considered but not built. This file grows with the code.

## Acceptance criteria

To be written once the engine can show each verdict with a test.

## Approaches abandoned mid-build

| Approach | What happened | Replaced by |
|---|---|---|
| **Default report showing everything** (commit `52093cb`) | The first console report printed, for every day, the restated balance, available balance and interest accrual alongside the four items the brief asks for, followed by the knowledge-cutoff and interest tables. Reviewing it against the brief's wording ("prints, per day: closing ledger balance, fee assessments, authorization states, and errors"), the required items were mixed in with roughly twice as many extra lines, making them harder to find. Nothing was wrong, but the default answered more than the question. | The default now prints exactly the four required items per day plus the final balances. The extras moved behind `--detail` (restated, available, interest, cutoff and interest tables); `--trace` is unchanged. Nothing was removed, and `ReportTest` checks that the default contains only the required items. |

## Approaches considered but not built

| Approach | Why not chosen |
|---|---|
| Read the event stream from an external file (e.g. `events.csv`) instead of `Fixture.java` | The stream is a fixed list of 10 events from the brief. A file would need a parser, its own error handling (bad rows, bad numbers, missing columns) and its own tests, all extra code to explain, for no gain on this input. Events written in Java are checked by the compiler. A file loader is a reasonable production change if other streams must be replayed without recompiling. |
