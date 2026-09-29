package io.muleshield.core.risk;

/** What happens to a payment. Step-up is orthogonal: an allowed or held payment may also need a passkey signature. */
public enum Action {
    /** Execute now. */
    ALLOW,
    /** Accept but don't release until {@code holdUntil}; the payer is told why and can cancel. */
    HOLD,
    /** Refuse, with the reason shown to the payer. */
    DECLINE
}
