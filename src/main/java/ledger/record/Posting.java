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
 * @param sourceId the source event that caused it, or null for engine-generated postings (fees)
 * @param key      unique business key: a fee ("FEE|ACC-001|D2"), a reversal ("REV|E7") or an instalment
 *                 child ("E10#1"); null otherwise
 * @param reversesSequence for REVERSAL postings, the sequence of the posting being cancelled; else null
 * @param cause    human-readable reason: which event or close produced this posting
 */
public record Posting(
        long sequence,
        int recordedDay,
        String accountId,
        PostingType type,
        Money amount,
        int valueDay,
        String sourceId,
        String key,
        Long reversesSequence,
        String cause) implements LedgerRecord {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(cause, "cause");
    }
}
