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

## 2026-10-20T22:39:53Z — Append-only log, postings and balances
- New package `ledger.record`: `LedgerRecord` (every record has a sequence number and the day it was
  recorded), `Posting`/`PostingType`, `Outcome` and `ErrorCode`. `ErrorCode` lives here, not in the engine,
  because `Outcome` stores it; that keeps the dependency one-way (engine → record).
- New package `ledger.engine`: `LedgerEngine.submit` validates an event and appends records to one list
  that is never edited; `LedgerView.balance(account, day, cutoff)` sums postings with value day ≤ day and
  sequence ≤ cutoff. Balances are computed by scanning, not stored (simple and checkable; O(records)).
- Only CREDIT and DEBIT are handled; the other kinds throw "not implemented yet" until their step.
- Bad input becomes a REJECTED outcome with an error code and moves no money. Blank source ids are
  rejected; resubmitting the same event returns the original outcome; reusing an id for a different event
  is rejected. `LedgerView` copies its inputs so a caller cannot change it afterwards.
- Engine clock: the latest booked day seen; it never moves back (a late event keeps the later day).
- Tests: `PostingAndBalanceTest` (Day 1 = 250.00; a debit dated back to Day 2 gives Day 2 = −370.00 and
  Day 3 = 30.00; cutoffs; append-only) and `InputValidationTest`. `mvn test` → 22 passed.
- Next: authorizations and settlement (Auth-A, Auth-Z, Auth-B).

## 2026-10-20T23:09:29Z — Authorizations and settlement
- `AuthorizationFact` record (APPROVED / DECLINED / SETTLED) with the numbers the decision used. A hold is
  not a posting: it lowers available balance, never the ledger balance. New outcome status DECLINED (a valid
  request the rule said no to), separate from REJECTED (invalid input).
- Authorization: approved iff ledger − active holds − requested ≥ 0, using everything known at that moment.
  Auth-A: 250 − 0 − 200 = 50 → approved. Auth-B: ledger −155 (E7 already posted) → declined. Fees are not
  in the engine yet, so Auth-B's evidence will change once they are.
- Settlement: one final capture against an approved, active authorization on the same account; capture ≤
  hold; debit the captured 185.00 and release the whole 200.00 hold (the 15.00 was only reserved, so no
  refund posting). The new-hold rule is not re-applied to a capture. E6 (Auth-Z, no authorization) →
  REJECTED UNKNOWN_AUTHORIZATION, no money moves (criterion 4).
- Tests: `AuthorizationTest` (7) and `SettlementTest` (6). `mvn test` → 35 passed.
- Checked the tests catch a real bug: changed the approval rule from `>= 0` to `> 0` → 3 failures; restored.
- Next: day close and overdraft fees, including backdated E7.
