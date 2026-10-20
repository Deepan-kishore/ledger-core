package ledger.engine;

import java.util.ArrayList;
import java.util.List;
import ledger.input.Account;
import ledger.input.Fixture;
import ledger.input.SourceEvent;
import ledger.money.CurrencyCode;
import ledger.money.Money;
import ledger.record.Posting;

/** Small helpers shared by the engine tests. Not a test class itself. */
final class Support {

    static final String A = Fixture.ACC_AED;
    static final String B = Fixture.ACC_BHD;

    private Support() {
    }

    static Money aed(String s) {
        return Money.of(CurrencyCode.AED, s);
    }

    static Money bhd(String s) {
        return Money.of(CurrencyCode.BHD, s);
    }

    /** All ten fixture events plus end-of-input finalization. */
    static LedgerEngine fullFixture() {
        LedgerEngine engine = fixtureThrough("E10");
        engine.finish();
        return engine;
    }

    static LedgerEngine engine() {
        return new LedgerEngine(Fixture.accounts(), LedgerEngine.standardFeeTariffs());
    }

    /** The fixture replayed through (and including) the given source id. */
    static LedgerEngine fixtureThrough(String lastSourceId) {
        LedgerEngine engine = engine();
        for (SourceEvent e : Fixture.events()) {
            engine.submit(e);
            if (e.sourceId().equals(lastSourceId)) {
                return engine;
            }
        }
        throw new IllegalArgumentException("No fixture event " + lastSourceId);
    }

    /** The fixture accounts plus an extra AED account ACC-003, for account-mismatch tests. */
    static LedgerEngine engineWithExtraAccount() {
        List<Account> accounts = new ArrayList<>(Fixture.accounts());
        accounts.add(new Account("ACC-003", CurrencyCode.AED, aed("0.00")));
        return new LedgerEngine(accounts, LedgerEngine.standardFeeTariffs());
    }

    /** An engine with one AED credit on Day 1. */
    static LedgerEngine fundedWith(String amount) {
        LedgerEngine engine = engine();
        engine.submit(SourceEvent.credit("F1", 1, A, "AED", amount, 1));
        return engine;
    }

    static Money now(LedgerEngine engine, String account, int day) {
        return engine.view().balance(account, day, engine.lastSequence());
    }

    static Money holdsNow(LedgerEngine engine, String account, int day) {
        return engine.view().activeHolds(account, day, engine.lastSequence());
    }

    static Money availableNow(LedgerEngine engine, String account, int day) {
        return engine.view().available(account, day, engine.lastSequence());
    }

    static List<Posting> postingsFor(LedgerEngine engine, String sourceId) {
        return engine.view().postings().stream().filter(p -> sourceId.equals(p.sourceId())).toList();
    }
}
