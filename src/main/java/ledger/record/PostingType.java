package ledger.record;

/** Why a posting exists. */
public enum PostingType {
    /** A CREDIT event: money in. */
    EXTERNAL_CREDIT,
    /** A DEBIT event: money out. */
    EXTERNAL_DEBIT,
    /** The captured amount of a settled authorization. */
    SETTLEMENT_DEBIT
}
