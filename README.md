# In-memory ledger (Java 17 assessment)

A small, runnable bank-ledger engine. It replays a fixed 10-event fixture over six business days for two
accounts, charges overdraft fees, handles card-style holds, reverses a backdated debit, splits a credit
into instalments, and capitalizes interest.

> **Status: engine and report complete.** All ten events replay and the console prints the per-day report.
> The intentionally failing test and the remaining documents are not written yet.
> This README grows with the code.

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
- The first build downloads JUnit and Maven plugins into `~/.m2`. That is build setup only; the program
  itself has no runtime dependencies and stores nothing.

## Commands (from a clean checkout)

```bash
mvn test                                      # runs the test suite (79 tests so far)
mvn package                                   # runs the suite again and builds the JAR
java -jar target/in-memory-ledger-1.0.0.jar   # replays the 10 events and prints the per-day report
java -jar target/in-memory-ledger-1.0.0.jar --trace   # also prints every record, event by event
```

## Reading the output

1. **`DAILY REPORT`**: for each day, for each account:
   - `closing ledger balance`: as known when that day closed (never edited later);
   - `restated`: the same day recomputed once all input is known (ACC-001 Day 2: 250.00 then 225.00);
   - `available balance`: ledger minus the holds active that day;
   - `overdraft fee`: fees belonging to that day, with the day each was found
     (`AED 25.00 effective Day2, discovered Day5`); on Day 5, also the earlier-day fees booked that day;
   - `authorizations`: each authorization's state as known that day, marked `(changed today)`;
   - `errors`: events rejected or declined that day (E6 on Day 4, E8 on Day 5);
   - `interest accrual`: that day's rounded interest, and on Day 6 the capitalized total.
   Empty sections print `none`.
2. **`ACC-001 DAY2 BALANCE AT NAMED KNOWLEDGE CUTOFFS`**: 250.00, 250.00, −370.00, −395.00, 225.00 (criterion 1
   is the −370.00 line: after E7, before any fee).
3. **`INTEREST`**: each day's balance, raw and rounded interest, and the capitalization credit.
4. **`FINAL BALANCES`**: ACC-001 AED 390.93, ACC-002 BHD 10.008 (currencies are never added together).
5. With `--trace`: every record the engine appended (`#22 rec Day5 ... value Day2` = record 22, written on
   Day 5, counting for Day 2).

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
