# NUMBERS

Every constant I chose, why that value, and why not half of it. The numbers the brief gives are listed
first only to separate them from the chosen ones.

## 1. Given by the brief (not chosen)

| Number | Where in the code | Meaning |
|---|---|---|
| 6 days, Day 1–Day 6 | `LedgerEngine.FIRST_DAY` / `LAST_DAY` | the window |
| AED 0.00, BHD 0.000 | `Fixture.accounts()` | opening balances |
| AED 25.00 | `LedgerEngine.standardFeeTariffs()` | overdraft fee |
| 0.04% per day | `InterestCalculator.DAILY_RATE = new BigDecimal("0.0004")` | interest rate (= 1/2500) |
| 2 decimals AED, 3 decimals BHD | `CurrencyCode` | how amounts are stored and rounded |
| E1–E10 amounts and days | `Fixture.events()` | the event stream |

## 2. Chosen by me

| Number / rule | Value | Why this value | Why not half |
|---|---|---|---|
| Smallest amount | 0.01 AED, 0.001 BHD | the currencies' own smallest unit (a fils) | 0.005 AED cannot be stored at 2 decimals; `Money` rejects it rather than round it |
| Fees per account per day | at most 1 | "assessed once per day per account" | there is no half fee; the key `FEE\|ACC-001\|D2` stops a second one |
| Fee charged when the closing balance is | below 0.00 | "is negative" | a 0.00 close is not negative, and no "half overdrawn" threshold (e.g. −12.50) is in the brief |
| Authorization approved when available after the hold is | 0.00 or more | "remains at or above zero" | a stricter cut-off (e.g. keep 0.01 back) is not in the brief; exactly 0.00 is approved |
| Interest earned when the closing balance is | above 0.00 | "positive balances only" | zero earns nothing; negative balances earn no negative interest |
| Interest rate divisor | 1 (the rate is per day) | the brief says "0.04% per day" | not 365 or 365/2: there is no annual rate to convert |
| Interest rounding | each day on its own, HALF_EVEN | "the rounded daily accruals must sum exactly to the capitalized total": six rounded days, credit = their sum | rounding the total once (0.92) or rounding half-up are alternatives, not halves; see AMBIGUITIES §16 |
| Interest credits per account | 1, at the end of Day 6, only if not zero | "capitalize as a single credit at end of Day 6" | half the interest at a mid-window date has no basis in the brief |
| Instalment leftover | 1 fil, on the **first** instalment | 10,000 ÷ 3 = 3,333 remainder 1; whole fils only | a half fil (0.0005) cannot exist; instalments differ by at most 1 fil |
| Captures per authorization | 1, at most the hold | the brief has one settlement per authorization | a partial capture would leave half a hold with no rule for releasing it |
| Reversals per event | 1 | a second reversal would credit the money twice | a partial reversal (e.g. 310.00 of 620.00) is not what E9 asks for |
| Value day allowed | Day 1 up to the latest booked day seen | no future-dated money | — |

## 3. Numbers chosen for tests

Each value sits on the edge of a rule, so the test fails if the rule is off by even one fil.

| Test | Values | Why these | Why not half |
|---|---|---|---|
| exactly-zero approval | balance 100.00, hold 100.00, then 0.01 | remaining exactly 0.00 is approved; one fil more is declined | a 50.00 hold would pass either way and prove nothing about the edge |
| several holds | 100.00 with holds 30.00 + 40.00; 30.01 declined, 30.00 approved | holds add up; edge at one fil | smaller holds would never reach the limit |
| capture above the hold | hold 100.00; capture 100.01 rejected, 100.00 accepted | the edge of "at most the hold" | 50.00 is safely inside and would not test the edge |
| earlier fee tips a later day | +100.00 Day 1, +10.00 Day 2, then −105.00 dated Day 1 | Day 2 is +5.00 before fees, −20.00 after the Day 1 fee, so a second fee | with −52.50, Day 1 stays positive and no fee happens at all |
| dip and recover | −50.00 then +100.00 on the same open day | no fee when the close is positive | — |
| zero close | +10.00 then −10.00 | 0.00 is not charged | — |
| repeated backdating | 10.00 credit, then three backdated debits | still one fee per day | — |
| HALF_EVEN ties | 12.50 → raw 0.005 → 0.00; 37.50 → raw 0.015 → 0.02 | exact halves, where HALF_EVEN and HALF_UP differ | 6.25 gives 0.0025, not a tie at 2 decimals |
| rounding drift | 22.50, 15.00, 20.00 → 0.01 each = 0.03 (exact 0.023); 12.50 twice → 0.00 (exact 0.010) | per-day rounding can land above or below the exact sum | — |
| unsettled hold | balance 100.00, hold 40.00 → Day 6 ledger 100.24, available 60.24 | interest is on the ledger balance (100.00 × 0.0004 × 6), not available | — |
| instalment splits | 1.000 / 3; 0.005 / 4; 10.000 / 4 | leftover order, and an even split | — |
| invalid instalments | count 0, −3; 0.002 / 3 | rejected before any instalment is posted | — |
| too many decimals | AED 1.005, BHD 10.0001 | one digit past the allowed decimals: rejected, not rounded | — |
| BHD with no fee | BHD −1.000 close on Day 1 | `MISSING_FEE_POLICY` once, no posting | — |
