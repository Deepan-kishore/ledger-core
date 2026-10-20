# REJECTED

Acceptance criteria refused (with reasons), approaches abandoned during the build, and approaches
considered but not built. This file grows with the code.

## Acceptance criteria

To be written once the engine can show each verdict with a test.

## Approaches abandoned mid-build

None yet.

## Approaches considered but not built

| Approach | Why not chosen |
|---|---|
| Read the event stream from an external file (e.g. `events.csv`) instead of `Fixture.java` | The stream is a fixed list of 10 events from the brief. A file would need a parser, its own error handling (bad rows, bad numbers, missing columns) and its own tests, all extra code to explain, for no gain on this input. Events written in Java are checked by the compiler. A file loader is a reasonable production change if other streams must be replayed without recompiling. |
