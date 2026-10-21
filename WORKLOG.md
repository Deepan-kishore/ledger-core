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

## 2026-10-20T23:38:55Z — Day close and overdraft fees
- Days close when an event for a later booked day arrives; `finish()` closes the rest at end of input. The
  brief gives no clock, only booked days. Each close assesses the fee, then saves a `DayClose` snapshot
  (balance, holds, available as known then) that is never edited.
- Fee: AED 25.00 when a day's closing balance (value date ≤ that day) is negative, dated to that day. At
  most one per account per day, guaranteed by a key such as `FEE|ACC-001|D2` (new `key` field on `Posting`).
- When a backdated posting lands on a closed day, closed days are re-checked from that day on, oldest
  first. A fee is a normal posting dated to its day, so it counts towards later days. The open day is only
  charged at its close, so a balance that dips and recovers during the day pays nothing.
- E7 now immediately causes fees for Day 2 (−370 → −395) and Day 4 (5 − 185 = −180 → −205); Day 3 stays at
  +5 and escapes. Day 5 is charged at its close (−205 → −230). Three fees, not one: criterion 2 is wrong.
- Named checkpoints (`before:`, `principal:`, `after:`, `close:DayN`): `principal:E7` is the moment after
  E7's debit and before its fees, which is where criterion 1's −370.00 lives.
- No BHD tariff is given, so a negative BHD close records `MISSING_FEE_POLICY` instead of inventing a fee.
- Two earlier tests changed because the behaviour changed: Day 2 after E7 is now −395 (−370 is checked at
  `principal:E7`), and Auth-B is now declined at −205 instead of −155, because the Day 2 and Day 4 fees
  already exist when E8 arrives.
- New tests: `OverdraftFeeTest` (8) and `DayCloseTest` (5). `mvn test` → 48 passed.
- Checked the tests catch a real bug: switched off the closed-day re-checks → 10 failures; restored.
- Next: reversal (E9) and instalments (E10).

## 2026-10-20T23:57:25Z — Reversal (E9) and instalments (E10)
- Reversal: appends an equal and opposite posting with the original's value day, linked to it (new
  `reversesSequence` field on `Posting`, key `REV|E7`). The original is never edited. Only an accepted CREDIT
  or DEBIT on the same account, with the same value day, can be reversed, and only once.
- E9 is booked Day 6, so Day 5 closes first (−205 → fee → −230), then +620 lands on Day 2. The three fees
  are kept (the brief has no refund rule), so ACC-001 ends at 390.00, not 465.00: criterion 6 is wrong.
  Auth-B stays declined; decisions are not revisited.
- Instalments: split in whole fils, 10,000 ÷ 3 = 3,333 remainder 1, extra fil to the first child →
  3.334, 3.333, 3.333 = exactly 10.000. Three × 3.334 = 10.002, so criterion 7 is wrong. All children are
  checked before any is posted; the parent event is not itself a posting.
- E10 arrives after E9, so it is processed on Day 6 (the clock never goes back) but counts for Day 5:
  the original Day 5 BHD close stays 0.000, restated Day 5 is 10.000.
- All ten fixture events now replay; `finish()` closes Day 6 positive at 390.00.
- New tests: `ReversalTest` (5) and `InstalmentTest` (5). `mvn test` → 58 passed.
- Checked the tests catch a real bug: gave the leftover fil to the last child instead of the first →
  2 failures; restored.
- Next: interest and capitalization.

## 2026-10-21T00:14:01Z — Interest and capitalization
- `InterestCalculator`: each day's interest = closing balance × 0.0004 if positive (the rate is per day, not
  divided by 365), rounded on its own with HALF_EVEN to the currency's decimals. The capitalized credit is
  the sum of the six rounded days, so "the rounded daily accruals must sum exactly" holds by definition
  and there is never a remainder to discard: criterion 8 is wrong.
- Basis: each day's balance with everything known at the end of the window (after E7, its fees, E9 and late
  E10), not the balance as known on that day. ACC-001: 250, 225, 625, 415, 390, 390 → 0.10 + 0.09 + 0.25 +
  0.17 + 0.16 + 0.16 = 0.93 (exact sum 0.918). ACC-002: 10.000 on Days 5–6 → 0.004 + 0.004 = 0.008.
- Order in `finish()`: close Days 1–5, charge any Day 6 fee, compute interest with a cutoff taken before the
  credit (so the credit earns no interest on itself), post one credit per account dated Day 6, then take
  the Day 6 snapshot last (marked post-capitalization). Final: ACC-001 AED 390.93, ACC-002 BHD 10.008.
- Interest uses the ledger balance, not available: an unsettled hold does not reduce it.
- One earlier test changed because interest now exists: the end-to-end balance went from 390.00 to 390.93.
- New tests: `InterestCalculatorTest` (8) and `CapitalizationTest` (7). `mvn test` → 73 passed.
- Checked the tests catch a real bug: switched HALF_EVEN to HALF_UP → 2 tiebreak tests failed, while the
  fixture results stayed 0.93 and 0.008 (no fixture day lands exactly on a half); restored.
- Next: the per-day console report.

## 2026-10-21T00:35:08Z — Per-day console report
- New package `ledger.report`: `Demo` replays the 10 fixture events through the engine, calls `finish()`, and
  prints a `DAILY REPORT`: for each day and both accounts, closing ledger balance (as known then and
  restated), available balance, overdraft fees (effective day and day found), authorization states as
  known that day, errors that day, and that day's interest; empty sections print "none". After it: Day 2
  at named cutoffs (250, 250, −370, −395, 225), the interest table and final balances. `--trace` adds every
  record, event by event. Every number comes from the engine; nothing is hard-coded.
- `pom.xml` main class is now `ledger.report.Demo`, so `java -jar target/in-memory-ledger-1.0.0.jar` runs it.
  41 records; final ACC-001 AED 390.93, ACC-002 BHD 10.008.
- New tests: `ReportTest` (6): every day has all four required sections for both accounts, balances,
  fee dates, authorization states and errors on the right days, console output, `--trace`.
  `mvn test` → 79 passed.
- Next: reviewing the output against the brief's wording.

## 2026-10-21T00:42:30Z — Report changed: default shows only what the brief asks for
- Reviewed the step 8 output (`52093cb`) against the brief: "prints, per day: closing ledger balance, fee
  assessments, authorization states, and errors". It printed those, but each day also carried the restated
  balance, available balance and interest, plus two tables after the report. Not a rule violation (the
  brief sets a minimum), but the required items were buried among extra lines.
- Changed the default to exactly the four items per day plus final balances. The extras are still there
  behind `--detail`; `--trace` is unchanged. Recorded in REJECTED.md as an approach abandoned mid-build.
- `ReportTest`: new checks that every default line is one of the required items, and that `--detail` brings
  the restated balances, the −370.00 cutoff and the interest table back. Its first run failed: the word
  "restated" appears in the default's hint line about `--detail`; narrowed the check to the report row.
  `mvn test` → 81 passed.

## 2026-10-21T01:00:17Z — Intentionally failing test: fees depend on when a correction arrives
- Question asked of the design: if the correction E9 reached the ledger a day earlier (Day 5 instead of
  Day 6), would the customer pay the same fees? Worked it through: with E9 on Day 5 it lands before Day 5
  closes, so Day 5 closes at −205 + 620 = +415 and is not charged; the Day 2 and Day 4 fees still exist
  because they are booked the moment E7 posts. AED 50.00 instead of AED 75.00 for the same facts.
- Cause: a day's fee is decided once, at that day's close, and never revisited (closed days are frozen so
  reported days never change). The cost is that charges depend on processing timing. Fixing it would mean
  basing fees on final balances and refunding by new postings, so past daily reports could change. The
  brief does not decide that trade-off, so the design is kept and the limitation is shown by a test.
- `ArrivalTimingChallenge`: replays the fixture twice, identical except E9's booked day, and asserts equal
  fees. Before that assertion it checks both runs are valid (E9 accepted, E6 rejected, E8 declined, same
  principal moved), so it can only fail on the design question. Annotated inline with the scenario, what
  it reveals, and what a fix would cost.
- Kept out of `mvn test` by its name (Surefire runs only *Test classes), so the normal build stays green and
  `mvn package` still builds the JAR. Verified: `mvn test` → 81 passed, challenge not mentioned;
  `mvn -Dtest=ArrivalTimingChallenge test` → 1 test, 1 failure, 0 errors, exit 1, failing at the final
  assertion: `expected: <AED 50.00> but was: <AED 75.00>`.
- README: command, expected result and a short explanation.
- Next: documentation (NUMBERS, AMBIGUITIES, criteria verdicts in REJECTED, architecture document).
