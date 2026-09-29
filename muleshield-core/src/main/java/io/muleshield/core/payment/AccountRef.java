package io.muleshield.core.payment;

import java.util.Locale;
import java.util.Objects;

/**
 * An account at a bank, in the shape of ISO 20022 {@code DbtrAcct}/{@code CdtrAcct} plus the
 * servicing agent. {@code bankId} is whatever identifies the bank in the scheme (a BIC, an IFSC
 * prefix, a UK sort code); {@code accountId} is an IBAN, an account number or a UPI VPA.   [ISO20022]
 */
public record AccountRef(String bankId, String accountId) {

    public AccountRef {
        Objects.requireNonNull(bankId, "bankId");
        Objects.requireNonNull(accountId, "accountId");
        bankId = bankId.strip().toUpperCase(Locale.ROOT);
        accountId = accountId.strip();
    }

    /** A stable string key, used for Kafka keys and store keys. */
    public String key() {
        return bankId + ":" + accountId;
    }

    public static AccountRef parse(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Not an account key: " + key);
        }
        return new AccountRef(key.substring(0, colon), key.substring(colon + 1));
    }

    @Override
    public String toString() {
        return key();
    }
}
