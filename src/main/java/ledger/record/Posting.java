package ledger.record;

import java.util.Objects;
import ledger.money.Money;

/**
 * A signed money movement on one account. Positive = credit (money in), negative = debit.
 *
 * <p>Two days matter and are different on purpose:
 * <ul>
 *   <li>{@code valueDay}: which day's balance the money belongs to (E7 is value Day 2).</li>
 *   <li>{@code recordedDay}: when the engine actually wrote it (E7 is recorded Day 5).</li>
 * </ul>
 *
 * @param sourceId the source event that caused it
 * @param cause    human-readable reason, e.g. the source id
 */
public record Posting(
        long sequence,
        int recordedDay,
        String accountId,
        PostingType type,
        Money amount,
        int valueDay,
        String sourceId,
        String cause) implements LedgerRecord {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(cause, "cause");
    }
}
