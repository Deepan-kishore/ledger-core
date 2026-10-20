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
