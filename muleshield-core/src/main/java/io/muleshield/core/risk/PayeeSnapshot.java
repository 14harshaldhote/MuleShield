package io.muleshield.core.risk;

import java.time.Instant;

/**
 * What is known about the receiving account. For the bank's own accounts this comes from the mule
 * detector; for accounts at other banks only the shared intelligence score and scam reports exist,
 * which is exactly the gap cross-bank sharing closes.
 *
 * @param accountOpenedAt   null when the account is at another bank
 * @param muleScore         0..1 from the mule detector (0 when unknown)
 * @param onDebitHold       the account is under a temporary debit hold or confirmed as a mule
 * @param scamReports30d    scam reports naming this account in the last 30 days
 * @param intelScore        0..1, the strongest signal other banks shared about it (pseudonymised)
 */
public record PayeeSnapshot(
        Instant accountOpenedAt,
        double muleScore,
        boolean onDebitHold,
        int inCount24h,
        int distinctSenders24h,
        int scamReports30d,
        double intelScore) {

    public static PayeeSnapshot unknown() {
        return new PayeeSnapshot(null, 0, false, 0, 0, 0, 0);
    }
}
