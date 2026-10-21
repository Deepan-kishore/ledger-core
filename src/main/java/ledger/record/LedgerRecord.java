package ledger.record;

/**
 * Anything appended to the engine's single append-only log. Every record gets the next value of
 * one increasing {@code sequence}, and the business day on which it was recorded (the engine's
 * clock, i.e. when the bank "found out").
 *
 * <p>A sequence number is a knowledge cutoff: "what did we know as of record N?" A day alone is
 * not enough, because several things can happen on the same day and we need to ask about the moment
 * between them.
 */
public sealed interface LedgerRecord permits Posting, Outcome, AuthorizationFact, DayClose, FeePolicyError, InterestReport {

    long sequence();

    int recordedDay();
}
