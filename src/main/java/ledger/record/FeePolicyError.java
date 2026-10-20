package ledger.record;

import java.util.Objects;
import ledger.money.Money;

/**
 * Recorded when a closing balance is negative but no fee tariff exists for the account's currency
 * (the brief gives only AED 25.00; a BHD amount or exchange rate is not invented).
 * No fee posting is made. At most one such record exists per account and day.
 */
public record FeePolicyError(
        long sequence,
        int recordedDay,
        String accountId,
        int effectiveDay,
        ErrorCode code,
        Money closingBalance,
        String cause) implements LedgerRecord {

    public FeePolicyError {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(closingBalance, "closingBalance");
    }
}
