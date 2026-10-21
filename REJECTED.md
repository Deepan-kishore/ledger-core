# REJECTED

The acceptance criteria I refused, with reasons, and the approaches I abandoned mid-build. After those:
approaches I considered but did not build, and, for completeness, the criteria I accepted.

## Refused criteria

Four of the eight criteria are wrong: 2, 6, 7 and 8.

### Criterion 2 — "E7 causes exactly one overdraft fee to be assessed, on Day 2"
The rule charges a fee on every day whose closing balance (value date ≤ that day) is negative. Once E7 is
known, re-checking the closed days oldest first:

| Day | Balance before its fee | Fee? | After |
|---|---|---|---|
| 2 | 250 − 620 = **−370** | yes | −395 |
| 3 | −395 + 400 = **+5** | no | +5 |
| 4 | 5 − 185 = **−180** | yes | −205 |
| 5 | **−205** (at its close) | yes | −230 |

So E7 causes **three** fees, on Days 2, 4 and 5, not one. Day 3 escapes only because the Day 2 fee counts on
it. Tests: `OverdraftFeeTest.criterion2E7CausesThreeFeesNotOne`,
`OverdraftFeeTest.e7ImmediatelyCausesFeesForClosedDays2And4ButNot3`.

### Criterion 6 — "After E9, all balances and fees return to their pre-E7 values"
E9 restores the 620.00 (a new +620.00 posting dated Day 2; E7 itself is never edited). The three fees stay:
the brief has no rule for refunding them. So restated Days 2–5 end 25 / 25 / 50 / 75 below their pre-E7
values, and ACC-001 ends at 390.00 before interest, not 465.00. Auth-B also stays declined; decisions are not
revisited. Append-only does *not* forbid a refund (it would be a new posting); the reason is that no refund
rule exists. Test: `ReversalTest.criterion6FeesAreKeptAfterTheReversal`.

### Criterion 7 — "The three BHD instalments in E10 must each be BHD 3.334"
3 × 3.334 = **10.002**, which credits 0.002 BHD that was never sent. Split in whole fils instead: 10,000 ÷ 3 =
3,333 remainder 1, with the leftover fil on the first instalment: **3.334 + 3.333 + 3.333 = 10.000**. Test:
`InstalmentTest.tenDinarsSplitAs3334_3333_3333`.

### Criterion 8 — "If the rounded daily interest accruals do not sum to the capitalized total, the remainder is discarded"
The brief's non-negotiable rule says "the rounded daily accruals must sum exactly to the capitalized total".
Criterion 8 assumes they might not and proposes discarding the difference. Here a mismatch cannot happen:
each day's accrual is that day's interest rounded on its own, and the credit is *defined* as their sum
(0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.16 = 0.93 for ACC-001). There is no remainder to discard. Test:
`InterestCalculatorTest.fixtureDailyAccrualsSumExactlyToNinetyThreeFils`.

## Approaches abandoned mid-build

### Default report showing everything (commit `52093cb`, replaced in `391f6a8`)
- **What existed:** the first console report printed, for every day, the restated balance, available
  balance and interest alongside the four items the brief asks for, followed by a knowledge-cutoff table and
  an interest table.
- **Why abandoned:** reviewed against the brief's wording ("prints, per day: closing ledger balance, fee
  assessments, authorization states, and errors"), the required items were mixed in with about twice as many
  extra lines and were harder to find. Nothing was wrong; the default answered more than the question.
- **Replaced by:** the default prints exactly the four items per day plus the final balances. The extras
  moved behind `--detail`; `--trace` is unchanged. `ReportTest` checks that the default holds only the
  required items.

## Approaches considered but not built

| Approach | Why not |
|---|---|
| Sort the events by booked or value day | The brief says "replayed in this order"; sorting would hide that E10 arrives after E9. |
| Charge only the backdated day, or never re-check closed days | Days 2, 4 and 5 are negative by the brief's own definition once E7 is known. |
| Refund fees when their cause is reversed | Allowed by append-only, but the brief has no refund rule. The effect is shown by the failing test. |
| Post a settlement without an authorization ("forced capture") | Contradicts criterion 4; kept as a production caveat. |
| Partial captures leaving the rest of the hold active | One settlement per authorization in the brief; a leftover hold would need an expiry rule. |
| Accrue interest each day on the balance known that day | Would pay interest on balances later shown wrong (Days 2–4) and none on Day 5. |
| Round the six-day interest total once (0.92) and spread it over the days | A day's amount would no longer be its own interest rounded (Day 6: 0.156 posted as 0.15). |
| Invent a BHD fee or convert AED 25.00 | No BHD fee or exchange rate is given. |
| Put the failing test inside `mvn test` | The default build would always fail and `mvn package` could not build the JAR. |
| Read the events from a file (e.g. `events.csv`) | A fixed 10-event stream; a file needs a parser, error handling and tests for no gain. A reasonable production change. |

## Accepted criteria (for completeness)

Every criterion was checked; these four hold.

- **Criterion 1** (Day 2 at end of Day 5, before any fee, is −370.00): E1 + E2 + E7 = 1,200 − 950 − 620 =
  −370.00, at the cutoff `principal:E7` (after E7's debit, before its fee). Test:
  `OverdraftFeeTest.criterion1Day2IsMinus370AfterE7BeforeAnyFee`.
- **Criterion 3** (Auth-A's Day 4 settlement accepted): approved on Day 2 (250 − 0 − 200 = 50 ≥ 0); 185.00 is
  within the 200.00 hold; the whole hold is released. Test:
  `SettlementTest.e5CapturesOneEightyFiveAndReleasesTheFullHold`.
- **Criterion 4** (unknown authorization rejected, no funds leave): E6 is rejected as `UNKNOWN_AUTHORIZATION`
  and nothing is posted. Caveat: real card networks sometimes force-post such a settlement and dispute it
  later; this core has no dispute process. Test: `SettlementTest.e6UnknownAuthorizationIsRejectedAndNoFundsLeave`.
- **Criterion 5** (if Auth-B is approved, its hold lowers available but not ledger): true as a rule, but Auth-B
  is declined (−205 − 0 − 90 < 0), so it never applies in the fixture; shown with a funded account. Tests:
  `AuthorizationTest.authBIsDeclinedInTheFixture`, `AuthorizationTest.criterion5HoldWouldLowerAvailableButNotLedger`.
