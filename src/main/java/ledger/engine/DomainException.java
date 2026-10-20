package ledger.engine;

import ledger.record.ErrorCode;

/**
 * Thrown inside the engine during validation when an event must be rejected for a business reason.
 * The engine converts it to a recorded {@code Outcome}; it never escapes {@link LedgerEngine#submit}.
 * Any other exception is a programming error and is allowed to fail loudly.
 */
final class DomainException extends RuntimeException {
    private final ErrorCode code;

    DomainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    ErrorCode code() {
        return code;
    }
}
