# In-memory ledger (Java 17 assessment)

A small, runnable bank-ledger engine. It replays a fixed 10-event fixture over six business days for two
accounts, charges overdraft fees, handles card-style holds, reverses a backdated debit, splits a credit
into instalments, and capitalizes interest.

Constants and why: [NUMBERS.md](NUMBERS.md). Open questions and how they were decided:
[AMBIGUITIES.md](AMBIGUITIES.md). Verdicts on the eight acceptance criteria, and approaches dropped:
[REJECTED.md](REJECTED.md). Timeline: [WORKLOG.md](WORKLOG.md).

## Package layout

| Package | Holds |
|---|---|
| `ledger.money` | `CurrencyCode` (AED 2 decimals, BHD 3) and `Money` (exact, scale-checked amounts) |
| `ledger.input` | `Account`, `EventKind`, `SourceEvent` (one event as supplied) and `Fixture` (the brief's E1–E10, in order) |
| `ledger.record` | the append-only records: `LedgerRecord`, `Posting`/`PostingType`, `AuthorizationFact`, `Outcome`, `DayClose`, `FeePolicyError`, `InterestReport`, `ErrorCode` |
| `ledger.engine` | `LedgerEngine` (validates events and appends records), `LedgerView` (balances, holds and available balance computed from the log) and `InterestCalculator` |
| `ledger.report` | `Demo`: replays the fixture and prints the per-day report |

## Prerequisites

- JDK 17 or newer (the build compiles with `--release 17`; it was verified on OpenJDK 25.0.2).
- Apache Maven 3.6.3+ (verified with Maven 3.10.0). No Maven wrapper is included.
- JUnit Jupiter 5.11.4, for tests only. Maven plugins: compiler 3.13.0, surefire 3.5.2, jar 3.4.2,
  resources 3.3.1.
- The first build downloads JUnit and Maven plugins into `~/.m2`. That is build setup only; the program
  itself has no runtime dependencies and stores nothing.

## Commands (from a clean checkout)

```bash
mvn test                                      # runs the test suite (84 tests)
mvn package                                   # runs the suite again and builds the JAR
java -jar target/in-memory-ledger-1.0.0.jar   # replays the 10 events and prints the per-day report
java -jar target/in-memory-ledger-1.0.0.jar --detail   # adds restated balances, cutoffs and interest
java -jar target/in-memory-ledger-1.0.0.jar --trace    # adds every record, event by event
mvn -Dtest=ArrivalTimingChallenge test       # the intentionally failing test: 1 failure, exit 1
```

How replay time grows with volume, and how it was measured: [bench/README.md](bench/README.md).

## Reading the output

**Default:** exactly what the brief asks for. `DAILY REPORT` has one block per day with both accounts:
- `closing ledger balance`: as known when that day closed (never edited later); Day 6 includes the interest
  credit;
- `overdraft fee`: fees belonging to that day, with the day each was found
  (`AED 25.00 effective Day2, discovered Day5`); on Day 5, also `earlier-day fees booked` that day;
- `authorizations`: each authorization's state as known that day, marked `(changed today)`;
- `errors`: events rejected or declined that day (E6 on Day 4, E8 on Day 5).

Empty sections print `none`. Then `FINAL BALANCES`: ACC-001 AED 390.93, ACC-002 BHD 10.008 (currencies are
never added together).

**`--detail`** adds:
- per day, `restated` (the same day recomputed once all input is known: ACC-001 Day 2 250.00 then 225.00),
  `available balance` (ledger minus that day's holds) and `interest accrual`;
- `ACC-001 DAY2 BALANCE AT NAMED KNOWLEDGE CUTOFFS`: 250.00, 250.00, −370.00, −395.00, 225.00. Criterion 1 is
  the −370.00 line: after E7, before any fee;
- `INTEREST`: each day's balance, raw and rounded interest, and the capitalization credit.

**`--trace`** adds every record the engine appended (`#22 rec Day5 ... value Day2` = record 22, written on
Day 5, counting for Day 2). The flags can be combined.

## Expected results

| What | Value |
|---|---|
| ACC-001 closing balances as closed, Days 1–6 | 250.00, 250.00, 650.00, 465.00, −230.00, 390.93 (Day 6 includes interest) |
| Auth-A | 250.00 − 0.00 − 200.00 = 50.00 ≥ 0 → approved; settled by E5 for 185.00, whole hold released |
| E6 (Auth-Z) | rejected, no money moves |
| Day 2 after E7, before any fee (criterion 1) | −370.00 |
| Fees | Day 2 and Day 4 (found on Day 5 when E7 arrives), Day 5 (at its close): AED 75.00 |
| Auth-B | −205.00 − 0.00 − 90.00 = −295.00 < 0 → declined |
| ACC-001 restated, Days 1–6 | 250.00, 225.00, 625.00, 415.00, 390.00, 390.00 |
| ACC-001 interest | 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.16 = 0.93 |
| E10 | 3.334 + 3.333 + 3.333 = 10.000; processed Day 6, counts for Day 5 |
| ACC-002 interest | 0.004 + 0.004 = 0.008 |
| Final | ACC-001 AED 390.93, ACC-002 BHD 10.008 |

## Balance formula

```
balance(account, day, cutoff)   = opening + sum of postings where valueDay <= day and sequence <= cutoff
available(account, day, cutoff) = balance(...) - active holds
approve a new hold              iff  ledger - active holds - requested hold >= 0
overdraft fee                   AED 25.00, at most once per account per day whose closing balance < 0,
                                dated to that day
interest(day)                   closing balance x 0.0004 if positive, rounded HALF_EVEN to the currency
capitalized                     sum of the six daily amounts, one credit per account dated Day 6
```

Days close when an event for a later day arrives (`finish()` closes the rest). When a backdated posting
lands on an already-closed day, closed days are re-checked from that day onwards, oldest first, so a fee
on an earlier day counts towards the later ones. The open day is only charged when it closes.

A reversal appends an equal and opposite posting with the original's value day, linked to it; the
original is never edited, and fees it caused are kept. An instalment credit is split in whole minor units,
the earliest children taking any leftover unit (BHD 10.000 / 3 = 3.334, 3.333, 3.333).

Interest is computed once, at the end of Day 6, on each day's closing balance with everything known by
then (after all events and fees, but before the interest credit itself). Final balances: ACC-001 AED 390.93
(0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.16 = 0.93 interest), ACC-002 BHD 10.008.

Every record in the log gets the next sequence number. The *cutoff* says "as known up to record N", which
a day number alone cannot express when several things happen on one day.

## The intentionally failing test

`ArrivalTimingChallenge` is a failing test against this design, kept out of the normal suite on purpose
(its name does not match Surefire's `*Test` pattern, so `mvn test` stays green and `mvn package` still
builds the JAR). Run it on its own:

```bash
mvn -Dtest=ArrivalTimingChallenge test
```

Expected result: `Tests run: 1, Failures: 1, Errors: 0`, exit code 1,
`expected: <AED 50.00> but was: <AED 75.00>`.

**Scenario.** The bank wrongly debits AED 620.00 (E7) and corrects it (E9). Whether the correction reaches
the ledger late on Day 5 or early on Day 6 depends on the bank's own processing, not the customer. The test
replays the fixture twice, identical except for E9's day, and expects the same overdraft fees.

| | Correction on Day 6 (the fixture) | Correction on Day 5 |
|---|---|---|
| Day 2 and Day 4 fees | charged when E7 posts | charged when E7 posts |
| Day 5 at its close | −205.00 → fee | +415.00 → no fee |
| Total fees | **AED 75.00** | **AED 50.00** |

**What it reveals.** A fee is charged as soon as a day is known to be negative, and once charged it is
never taken back, so the charge depends on *when* a correction arrives, not only on *what* it corrects.
The cause is the fee rule, not the frozen day snapshots: the brief gives no rule for refunding a fee. A fix
would append a +25.00 refund posting whenever a correction makes a charged day positive again, leaving the
original fee and the snapshots untouched; both runs would then net AED 0.00. The cost is a refund rule the
brief does not give, and customers seeing a fee charged and later refunded.
Before its final assertion, the test checks that both runs are valid and moved the same money, so it can
only fail on the design question.
