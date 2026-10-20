# WORKLOG

All times are UTC, taken with `date -u` while working.

## 2026-10-20T19:40:20Z — Project setup
- Rebuilding the ledger from scratch in a fresh repository, working step by step with AI assistance.
- Set up Maven (Java 17, JUnit 5 for tests only), .gitignore and a README that only describes what exists.
- `mvn package` passes with no code yet.
- Next: money and currency types.

## 2026-10-20T19:45:21Z — Money and currency types
- Package layout decided: one package per kind of thing (money, input, record, engine, report) instead of
  one flat folder, so each package has a deliberate public surface. Only `ledger.money` exists so far.
- `CurrencyCode`: AED 2 decimals, BHD 3 (from the brief).
- `Money`: `BigDecimal` rather than a `long` of minor units — exact and readable ("1200.00"), and the amounts
  here are tiny so speed does not matter. Too many decimals (AED 1.005) is rejected, not rounded, so money
  can never appear or disappear silently. AED and BHD can never be added or compared.
- `MoneyTest`: 6 tests (scale, rejection, exact arithmetic, comparison, minor units, currency lookup).
  `mvn test` → 6 passed.
- Next: accounts, source events and the 10-event fixture.

## 2026-10-20T19:56:49Z — Input: accounts, events, fixture
- New package `ledger.input`: `Account` (id, currency, opening balance; the opening must be in the account's
  currency), `EventKind`, `SourceEvent` and `Fixture`.
- The fixture is the brief's E1–E10 written as data, in the order given ("replayed in this order"). It is not
  sorted, so E10 (booked Day 5) stays after E9 (booked Day 6). Kept in code rather than a file: no parser to
  write and test for a fixed 10-event stream.
- Amounts stay as text in `SourceEvent` ("620.00"), so a bad amount can reach the engine and be recorded as a
  rejected event instead of failing while the event is built.
- E10 is modelled as its own kind, INSTALMENT_CREDIT (the brief calls it a CREDIT "posted as three equal
  instalments"); to note in AMBIGUITIES.md.
- `FixtureTest`: 5 tests (order, every field, E7/E9 backdated, openings, currency check). `mvn test` → 11 passed.
- Next: append-only records, postings and balance by value date.
