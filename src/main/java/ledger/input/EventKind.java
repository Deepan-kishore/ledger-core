package ledger.input;

/**
 * Kinds of source event the fixture can send.
 * <ul>
 *   <li>CREDIT / DEBIT: money in / money out, posted immediately.</li>
 *   <li>AUTHORIZATION: a card-style request to reserve ("hold") funds. Moves no money.</li>
 *   <li>SETTLEMENT: the final capture of an earlier approved authorization. Debits money, releases the hold.</li>
 *   <li>REVERSAL: undoes an earlier CREDIT or DEBIT by appending an equal and opposite posting.</li>
 *   <li>INSTALMENT_CREDIT: one total credited as N child postings whose sum is exactly the total.</li>
 * </ul>
 */
public enum EventKind {
    CREDIT,
    DEBIT,
    AUTHORIZATION,
    SETTLEMENT,
    REVERSAL,
    INSTALMENT_CREDIT
}
