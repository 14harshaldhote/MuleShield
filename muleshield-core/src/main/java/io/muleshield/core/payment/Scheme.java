package io.muleshield.core.payment;

/** Real-time payment rails the engine is built for. All are push payments that settle in seconds. */
public enum Scheme {
    /** India, Unified Payments Interface (NPCI). */
    UPI,
    /** India, Immediate Payment Service (NPCI). */
    IMPS,
    /** United Kingdom, Faster Payments. */
    FPS,
    /** Euro area, SEPA Instant Credit Transfer. */
    SCT_INST,
    /** Singapore, FAST. */
    FAST,
    /** Australia, New Payments Platform. */
    NPP,
    /** United States, RTP / FedNow. */
    RTP
}
