# AMBIGUITIES

Places where the brief can be read more than one way. Each entry gives the realistic alternatives, the
decision implemented, what it changes, and the tests that pin it. Only the decision is implemented.

**The ones that change numbers:** retroactive fees (§5, §6), which balance an authorization sees (§12),
fees after a reversal (§8), settlement without an authorization (§10), interest basis and rounding (§15,
§16), late E10 and when days close (§1, §3), and the missing BHD tariff (§9).

---

## 1. Event order and the late E10
- **Alternatives**: (a) process in the order given; (b) sort by booked day; (c) sort by value day.
- **Decision**: (a). The brief says "replayed in this order". E10 (booked Day 5) comes after E9 (booked
  Day 6), so it is processed on Day 6; the engine's clock never goes back. Its money still counts for Day 5.
- **Impact**: ACC-002's Day 5 closes at 0.000 (E10 not yet known) and is 10.000 once restated. Sorting would
  hide the late arrival.
- **Tests**: `FixtureTest.fixtureHasExactlyTenEventsInSuppliedOrder`,
  `InstalmentTest.lateE10IsProcessedOnDay6ButCountsForDay5`.

## 2. "Evaluated at end of Day 5 and before any fee" — a moment, not a day
- **Alternatives**: query balances by day only; by record sequence number.
- **Decision**: every record gets the next sequence number, and a balance is asked "as of" a sequence:
  `balance(account, day, cutoff)`. Named cutoffs mark moments such as `principal:E7` (after E7's debit,
  before its fees).
- **Impact**: criterion 1's −370.00 is expressible. A day alone cannot separate E7 from the fees it causes,
  since both are recorded on Day 5.
- **Tests**: `PostingAndBalanceTest.aCutoffShowsWhatWasKnownAtThatMoment`,
  `OverdraftFeeTest.criterion1Day2IsMinus370AfterE7BeforeAnyFee`.

## 3. When a day closes
- **Alternatives**: (i) when the first event of a later booked day arrives; (ii) at a fixed time; (iii) only at
  the end of input.
- **Decision**: (i), with `finish()` closing whatever is left at the end of input. The brief gives no clock,
  only booked days. Skipped days are closed in order, each as of its own day.
- **Impact**: E9 (Day 6) closes Day 5 before E9 is applied, so Day 5 closes at −230.00. Day 6 stays open
  while late E10 arrives.
- **Rejected events**: the clock moves before the event's content is checked. A valid booked day means that
  day has arrived, even if the event is then rejected (a bad amount, an unknown account), so earlier days
  close and can be charged. The alternative, checking first and moving the clock only for accepted events,
  would let a day stay open because its only event was malformed. Not moved by: a booked day outside
  Day 1–6, a blank source id, or an event after the window is finished.
- **Tests**: `DayCloseTest.skippedDaysAreClosedInOrder`, `ReversalTest.e9ClosesDay5FirstSoItsFeeIsAlreadyCharged`,
  `DayCloseTest.aRejectedEventWithAValidBookedDayStillMovesTheClock`,
  `DayCloseTest.anInvalidBookedDayDoesNotMoveTheClock`.

## 4. Original vs restated balances
- **Alternatives**: overwrite past days; keep only the latest view; keep both.
- **Decision**: both. Each close is saved as a frozen `DayClose` snapshot that is never edited; restated
  balances are computed from the postings with a later cutoff.
- **Impact**: ACC-001 Day 2 is 250.00 as closed and 225.00 restated. The default report shows the closing
  balance; `--detail` adds the restated one.
- **Tests**: `DayCloseTest.aClosedDaySnapshotIsNeverEditedByLaterBackdating`,
  `ReportTest.detailAddsRestatedBalancesAvailableAndInterest`.

## 5. Fees for days that are already closed
- **Alternatives**: (a) charge only the backdated day (criterion 2's reading); (b) re-check every closed day
  from the value day on, oldest first; (c) never re-check closed days (only Day 5's fee).
- **Decision**: (b). The brief defines a day's balance as "all entries with value_date ≤ that day", so once
  E7 is known, Days 2 and 4 *are* negative days. A fee is dated to the day it belongs to; the day it was
  found is recorded separately.
- **Impact**: E7 causes fees for Day 2 and Day 4 (found on Day 5) and Day 5 (at its close): three fees.
- **Tests**: `OverdraftFeeTest.e7ImmediatelyCausesFeesForClosedDays2And4ButNot3`,
  `OverdraftFeeTest.criterion2E7CausesThreeFeesNotOne`.

## 6. Do earlier fees count on later days?
- **Alternatives**: check every day against the event money only; include earlier fees.
- **Decision**: include them. A fee is an ordinary posting dated to its day, so later days see it. At most
  one fee per account per day (fee key `FEE|ACC-001|D2`), however often a day is re-checked.
- **Impact**: Day 3 is −395 + 400 = +5 (no fee). Without the Day 2 fee it would be +30; same outcome here,
  but a different fixture could tip a day negative.
- **Tests**: `OverdraftFeeTest.anEarlierFeeCanTurnALaterSmallPositiveDayNegative`,
  `OverdraftFeeTest.repeatedBackdatingNeverChargesTheSameDayTwice`.

## 7. Charging the day that is still open
- **Alternatives**: charge as soon as the balance goes negative; charge at the close.
- **Decision**: at the close. The rule is about the *closing* balance.
- **Impact**: Day 5 is negative from E7 onwards but charged only when E9 closes it.
- **Tests**: `OverdraftFeeTest.theOpenDayIsChargedOnlyWhenItCloses`,
  `OverdraftFeeTest.aBalanceThatDipsAndRecoversBeforeTheCloseIsNotCharged`.

## 8. Fees after the reversal
- **Alternatives**: (a) keep the fees; (b) refund them with new +25.00 postings when their cause is reversed;
  (c) delete them (forbidden: append-only).
- **Decision**: (a). The brief's fee rule has no refund clause, so none is invented. Append-only does not
  forbid (b); it is a business decision.
- **Impact**: ACC-001 ends at 390.00 before interest, not 465.00 (criterion 6 refused). Because a fee, once
  charged, is never taken back, the fees also depend on when E9 arrives: AED 75.00 on Day 6, AED 50.00 on
  Day 5. That is the intentionally failing test. The cause is the missing refund rule, not the frozen day
  snapshots: refunds would be new postings and the snapshots would stay as they are.
- **Tests**: `ReversalTest.criterion6FeesAreKeptAfterTheReversal`; `ArrivalTimingChallenge` (fails on purpose).

## 9. No BHD overdraft fee is given
- **Alternatives**: invent BHD 25.000; convert AED 25.00 with an invented exchange rate; record an error.
- **Decision**: record `MISSING_FEE_POLICY` once per account and day, and post nothing. The engine takes a
  fee per currency, so a BHD fee can be configured if one is ever given.
- **Impact**: none for the fixture (ACC-002 never goes negative).
- **Tests**: `DayCloseTest.negativeBhdCloseRecordsMissingFeePolicyOncePerDayAndNoFee`.

## 10. Settlement with no authorization (E6)
- **Alternatives**: (a) reject it; (b) post it anyway ("forced capture", as card networks sometimes do);
  (c) invent an authorization.
- **Decision**: (a): `UNKNOWN_AUTHORIZATION`, no posting, no Auth-Z record. This matches criterion 4 and the
  brief's note on E6. A production issuer would also need a forced-capture-and-dispute path.
- **Tests**: `SettlementTest.e6UnknownAuthorizationIsRejectedAndNoFundsLeave`.

## 11. Settling for less than the hold
- **Alternatives**: keep the 15.00 left over as a hold; refund 15.00; allow a capture above the hold.
- **Decision**: one final capture per authorization, no larger than the hold; debit 185.00 and release the
  whole 200.00. The 15.00 was reserved, never taken, so there is nothing to refund. A capture above the hold
  is rejected. A capture is not re-checked against available balance: the hold already reserved the money.
- **Tests**: `SettlementTest.e5CapturesOneEightyFiveAndReleasesTheFullHold`,
  `SettlementTest.captureAboveTheHoldIsRejectedButExactlyTheHoldIsAllowed`,
  `SettlementTest.captureIsNotSubjectToTheNewAuthorizationRule`.

## 12. Which balance an authorization is judged against
- **Alternatives**: the balance known at that moment (including backdated postings and the fees they caused);
  that day's balance only; a balance without fees.
- **Decision**: everything known at that moment. An authorization is a real-time decision. The decision and
  the numbers it used are stored, and never revisited when later events change the past.
- **Impact**: Auth-B is judged at −205.00 (E7 and the Day 2 and Day 4 fees are already booked) →
  −205 − 0 − 90 = −295 < 0, declined. Without the fees it would be −155, still declined. When E9 restores the
  money, Auth-B stays declined.
- **Tests**: `AuthorizationTest.authBIsDeclinedInTheFixture`, `ReversalTest.criterion6FeesAreKeptAfterTheReversal`.

## 13. Hold expiry
- **Alternatives**: expire after N days; never expire within the window.
- **Decision**: the brief gives no expiry, so none is invented; an unsettled hold stays active through Day 6.
  Auth-B is declined in the fixture, so this is shown with a funded account.
- **Tests**: `CapitalizationTest.interestIsEarnedOnTheLedgerBalanceNotAvailable`.

## 14. What a reversal may target
- **Decision**: only an accepted CREDIT or DEBIT on the same account, with the same value day, and only once.
  Holds, settlements, instalments, fees, interest and other reversals cannot be reversed. The reversal is a
  new opposite posting linked to the original; the original is never edited.
- **Tests**: `ReversalTest.invalidAndDuplicateReversalsAreRejectedWithoutPostings`,
  `ReversalTest.aCreditCanBeReversedToo`.

## 15. Interest: which balance, and when
- **Alternatives**: accrue each day at its close on the balance known then; compute all six days at the end
  on the final balances; use available balance.
- **Decision**: compute once at the end of Day 6, on each day's *ledger* balance with everything known by
  then, using a cutoff taken before the interest credit (so the credit earns no interest on itself). The
  Day 6 snapshot is taken after the credit.
- **Impact**: basis 250, 225, 625, 415, 390, 390. Accruing on as-known balances would pay interest on Days 2–4
  balances later shown to be wrong, and none on Day 5 (−230).
- **Tests**: `CapitalizationTest.interestUsesEachDaysBalanceWithEverythingKnownAtTheEnd`,
  `CapitalizationTest.theCreditDoesNotEarnInterestOnItself`,
  `CapitalizationTest.theDay6SnapshotIsTakenAfterTheCredit`.

## 16. Interest: how to round
- **Alternatives**: (a) round each day, capitalize the sum; (b) round the six-day total once and spread it
  back over the days; rounding mode HALF_UP or HALF_EVEN.
- **Decision**: (a) with HALF_EVEN. The brief speaks of "rounded daily accruals" that "sum exactly to the
  capitalized total"; with (a) each accrual is its own day's interest rounded and the total is their sum by
  definition. HALF_EVEN sends exact halves to the even digit, so ties do not always favour one side.
- **Impact**: 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.16 = 0.93 (exact 0.918). (b) would give 0.92. No fixture day
  is an exact half, so HALF_UP gives the same numbers here.
- **Tests**: `InterestCalculatorTest.fixtureDailyAccrualsSumExactlyToNinetyThreeFils`,
  `InterestCalculatorTest.dailyRoundingUsesHalfEvenOnExactHalves`,
  `InterestCalculatorTest.roundingCanAlsoLandBelowTheExactSum`.

## 17. "Daily interest 0.04% per day"
- **Decision**: 0.0004 per day, not divided by 365: the brief states a daily rate. Positive balances only;
  zero and negative earn nothing.
- **Tests**: `InterestCalculatorTest.dailyRateIsExactlyOneTwentyFiveHundredth`,
  `InterestCalculatorTest.zeroAndNegativeBalancesEarnNothing`.

## 18. "Three equal instalments" (E10)
- **Alternatives**: three × 3.334 (10.002); remainder on the last instalment; remainder on the first.
- **Decision**: E10 is modelled as its own event kind (the brief calls it a CREDIT "posted as three equal
  instalments"). Split in whole fils, the first instalment taking the leftover fil: 3.334, 3.333, 3.333.
  "Equal" cannot be exact at 3 decimals; this is as equal as possible and sums exactly. All instalments are
  checked before any is posted.
- **Tests**: `InstalmentTest.tenDinarsSplitAs3334_3333_3333`, `InstalmentTest.otherSplitsAreDeterministic`,
  `InstalmentTest.invalidSplitsPostNoChildren`.

## 19. Zero
- **Decision**: event amounts must be greater than zero. A closing balance of exactly 0.00 is not negative (no
  fee) and not positive (no interest). An authorization that leaves exactly 0.00 available is approved
  ("at or above zero").
- **Tests**: `InputValidationTest.invalidEventsAreRejectedWithoutAnyPosting`, `OverdraftFeeTest.zeroIsNotNegative`,
  `AuthorizationTest.approvedWhenRemainingIsExactlyZeroAndDeclinedOneMinorUnitAbove`.

## 20. Bad input, duplicates and blank ids
- **Decision**: invalid input becomes a REJECTED outcome with an error code, and the rejected event itself
  posts nothing. (If its booked day is valid, the clock still moves and earlier days close: see §3.) Too
  many decimals is rejected, never rounded. Sending the same event again returns the original outcome; reusing an
  id for a different event is rejected; a blank id is rejected without moving the clock.
- **Tests**: `InputValidationTest.*`, `MoneyTest.excessPrecisionIsRejectedNotRounded`.

## 21. Value days later than the engine's clock
- **Decision**: a value day must be within Day 1 up to the latest booked day seen (no future dating).
- **Tests**: `InputValidationTest.invalidEventsAreRejectedWithoutAnyPosting` (case X8).

## 22. What the per-day output contains
- **Decision**: by default exactly the four items the brief lists, per day and account, plus final balances;
  `--detail` adds restated balances, available balance, interest and the cutoff table; `--trace` adds every
  record. See REJECTED.md for the first version, which printed everything by default.
- **Tests**: `ReportTest.byDefaultEachDayShowsOnlyTheFourItemsTheBriefAsksFor`,
  `ReportTest.detailFlagAddsCheckpointsAndInterest`.

## 23. Where the failing test runs
- **Alternatives**: inside `mvn test` (the default build is always red and `mvn package` cannot build the JAR);
  separately.
- **Decision**: separately, by name: `mvn -Dtest=ArrivalTimingChallenge test` (1 failure, exit 1).

## 24. Scope
- **Single writer**: one ordered caller; no locks or concurrency guarantees.
- **Single-entry**: signed postings per customer account; no bank-side accounts (fee income, interest
  expense).
- **No persistence**: all state is in memory and is gone when the program exits.
