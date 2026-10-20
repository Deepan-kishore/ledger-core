package ledger.input;

import java.util.Objects;

/**
 * One raw input event exactly as supplied. Amounts and currency stay as text so the engine
 * can validate them (and reject bad input as a recorded domain error) rather than failing
 * while the event is being built.
 *
 * @param sourceId        caller-supplied id such as "E7"; used for idempotency
 * @param bookedDay       the business day the source system says it booked the event (1..6)
 * @param kind            what the event asks for
 * @param accountId       target account
 * @param currencyText    e.g. "AED"; null for REVERSAL (the amount comes from the target)
 * @param amountText      e.g. "620.00"; non-negative magnitude, sign comes from {@code kind}; null for REVERSAL
 * @param valueDay        the day the money counts for when computing balances (may be earlier than booked)
 * @param authorizationId for AUTHORIZATION / SETTLEMENT, else null
 * @param targetSourceId  for REVERSAL, else null
 * @param instalmentCount for INSTALMENT_CREDIT, else 0
 */
public record SourceEvent(
        String sourceId,
        int bookedDay,
        EventKind kind,
        String accountId,
        String currencyText,
        String amountText,
        int valueDay,
        String authorizationId,
        String targetSourceId,
        int instalmentCount) {

    public SourceEvent {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(accountId, "accountId");
    }

    public static SourceEvent credit(String id, int booked, String account, String ccy, String amount, int value) {
        return new SourceEvent(id, booked, EventKind.CREDIT, account, ccy, amount, value, null, null, 0);
    }

    public static SourceEvent debit(String id, int booked, String account, String ccy, String amount, int value) {
        return new SourceEvent(id, booked, EventKind.DEBIT, account, ccy, amount, value, null, null, 0);
    }

    public static SourceEvent authorization(String id, int booked, String account, String authId,
                                            String ccy, String hold, int value) {
        return new SourceEvent(id, booked, EventKind.AUTHORIZATION, account, ccy, hold, value, authId, null, 0);
    }

    public static SourceEvent settlement(String id, int booked, String account, String authId,
                                         String ccy, String amount, int value) {
        return new SourceEvent(id, booked, EventKind.SETTLEMENT, account, ccy, amount, value, authId, null, 0);
    }

    public static SourceEvent reversal(String id, int booked, String account, String targetId, int value) {
        return new SourceEvent(id, booked, EventKind.REVERSAL, account, null, null, value, null, targetId, 0);
    }

    public static SourceEvent instalmentCredit(String id, int booked, String account, String ccy,
                                               String total, int value, int count) {
        return new SourceEvent(id, booked, EventKind.INSTALMENT_CREDIT, account, ccy, total, value, null, null, count);
    }
}
