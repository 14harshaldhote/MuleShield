package io.muleshield.core.payment;

import java.time.Instant;

/**
 * What the bank's app knows about the session that initiated the payment. These are the signals
 * behind the scams that dominate 2025-26 losses: a new device or a recent SIM swap (account
 * takeover), screen-sharing software running (remote-access scams), and a live phone call during
 * the payment ("digital arrest" and bank-impersonation scams keep the victim on the line).
 * <p>
 * The app reports them, so a compromised device can lie; they raise risk, they are never trusted
 * to lower it.
 *
 * @param deviceBoundAt when this device was bound to the customer (SIM binding in UPI apps)
 * @param simChangedAt  last SIM change reported by the operator, or null if none known
 */
public record SessionSignals(
        String deviceId,
        Instant deviceBoundAt,
        Instant simChangedAt,
        boolean remoteAccessActive,
        boolean onActiveCall) {

    public static SessionSignals trustedDevice(String deviceId, Instant boundAt) {
        return new SessionSignals(deviceId, boundAt, null, false, false);
    }
}
