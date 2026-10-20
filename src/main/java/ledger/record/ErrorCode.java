package ledger.record;

/** Stable, machine-readable reasons why an event was rejected. */
public enum ErrorCode {
    // input validation
    MISSING_SOURCE_ID,
    UNKNOWN_ACCOUNT,
    UNSUPPORTED_CURRENCY,
    CURRENCY_MISMATCH,
    INVALID_AMOUNT,
    EXCESS_PRECISION,
    NEGATIVE_AMOUNT,
    ZERO_AMOUNT,
    INVALID_BOOKED_DAY,
    INVALID_VALUE_DAY,
    // idempotency
    DUPLICATE_SOURCE_CONFLICT
}
