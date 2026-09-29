package io.muleshield.core.intel;

import java.time.Instant;

/**
 * What one bank tells the others about an account: a pseudonymous token, what was seen, and how
 * sure the bank is. No name, account number or transaction leaves the bank.
 *
 * @param confidence 0..1; a confirmed mule is 1, a model flag is its score
 */
public record SharedSignal(String token, String epoch, String fromBank, Kind kind, double confidence, Instant at) {

    public enum Kind {
        /** The bank placed a debit hold after review and confirmed the account as a mule. */
        MULE_CONFIRMED,
        /** The bank's model flagged the account; not yet reviewed. */
        MULE_SUSPECTED,
        /** A customer reported paying this account in a scam. */
        SCAM_REPORTED
    }

    public SharedSignal {
        if (!(confidence >= 0 && confidence <= 1)) {
            throw new IllegalArgumentException("confidence must be in 0..1");
        }
    }

    /** How much a receiving bank should weigh it: reviewed facts count fully, unreviewed flags less. */
    public double weight() {
        return switch (kind) {
            case MULE_CONFIRMED -> confidence;
            case SCAM_REPORTED -> 0.8 * confidence;
            case MULE_SUSPECTED -> 0.6 * confidence;
        };
    }
}
