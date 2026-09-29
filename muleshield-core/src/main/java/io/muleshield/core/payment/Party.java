package io.muleshield.core.payment;

import java.util.Objects;

/** ISO 20022 {@code Dbtr}/{@code Cdtr}: the name the payer typed or the bank holds, and the account. */
public record Party(String name, AccountRef account) {

    public Party {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(account, "account");
    }
}
