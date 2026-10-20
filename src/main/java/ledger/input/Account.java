package ledger.input;

import java.util.Objects;
import ledger.money.CurrencyCode;
import ledger.money.Money;

/** A customer account: identifier, its single currency, and the balance it opened the window with. */
public record Account(String id, CurrencyCode currency, Money opening) {
    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(opening, "opening");
        if (opening.currency() != currency) {
            throw new IllegalArgumentException("Opening balance currency must equal account currency");
        }
    }
}
