package ledger.record;

import java.util.List;
import java.util.Objects;
import ledger.input.EventKind;

/**
 * The engine's answer to one submitted source event, kept in the log forever.
 *
 * <ul>
 *   <li>ACCEPTED: the event took effect; {@code effectSequences} lists the records it created.</li>
 *   <li>REJECTED: invalid or not allowed. No money moved; {@code errorCode} says why.</li>
 * </ul>
 *
 * @param inputPosition 1-based position in the order events were submitted (separate from sequence)
 */
public record Outcome(
        long sequence,
        int recordedDay,
        String sourceId,
        int inputPosition,
        EventKind kind,
        Status status,
        ErrorCode errorCode,
        String message,
        List<Long> effectSequences) implements LedgerRecord {

    public enum Status { ACCEPTED, REJECTED }

    public Outcome {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        effectSequences = List.copyOf(effectSequences);
    }
}
