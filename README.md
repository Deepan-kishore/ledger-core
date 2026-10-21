# In-memory ledger (Java 17 assessment)

A small, runnable bank-ledger engine. It replays a fixed 10-event fixture over six business days for two
accounts, charges overdraft fees, handles card-style holds, reverses a backdated debit, splits a credit
into instalments, and capitalizes interest.

> **Status: engine complete.** All ten events replay, with fees, reversals, instalments and interest
> capitalization written and tested. The console report is not written yet.
> This README grows with the code.

## Package layout

| Package | Holds |
|---|---|
| `ledger.money` | `CurrencyCode` (AED 2 decimals, BHD 3) and `Money` (exact, scale-checked amounts) |
| `ledger.input` | `Account`, `EventKind`, `SourceEvent` (one event as supplied) and `Fixture` (the brief's E1–E10, in order) |
| `ledger.record` | the append-only records: `LedgerRecord`, `Posting`/`PostingType`, `AuthorizationFact`, `Outcome`, `DayClose`, `FeePolicyError`, `InterestReport`, `ErrorCode` |
| `ledger.engine` | `LedgerEngine` (validates events and appends records), `LedgerView` (balances, holds and available balance computed from the log) and `InterestCalculator` |

## Prerequisites

- JDK 17 or newer (the build compiles with `--release 17`; it was verified on OpenJDK 25.0.2).
- Apache Maven 3.6.3+ (verified with Maven 3.10.0). No Maven wrapper is included.
- The first build downloads JUnit and Maven plugins into `~/.m2`. That is build setup only; the program
  itself has no runtime dependencies and stores nothing.

## Commands (from a clean checkout)

```bash
mvn test       # compiles and runs the test suite (73 tests so far)
mvn package    # runs the suite again and builds target/in-memory-ledger-1.0.0.jar
```

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
