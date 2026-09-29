package io.muleshield.core.risk;

import java.util.Arrays;
import java.util.List;
import java.util.function.DoublePredicate;

/**
 * The payment model's inputs, in the order the model sees them. The same class computes them for
 * training (the simulator) and for serving (risk-service), so the model is never fed a feature
 * computed differently from the one it learned: no training/serving skew.
 * <p>
 * Each feature names the reason it stands for and when its value is worth reporting; the model's
 * per-feature contributions decide which reasons a payment actually gets.
 */
public enum PaymentFeature {
    AMOUNT_SCALED_LOG(ReasonCode.NEW_PAYEE_HIGH_VALUE, v -> v >= Math.log10(0.4)),
    AMOUNT_Z(ReasonCode.AMOUNT_UNUSUAL, v -> v >= 2),
    PAYEE_NEW(ReasonCode.NEW_PAYEE_HIGH_VALUE, v -> v >= 1),
    PAYER_TX_1H(ReasonCode.VELOCITY, v -> v >= 3),
    PAYER_AMOUNT_1H_LOG(ReasonCode.VELOCITY, v -> v >= Math.log10(1.5)),
    PAYER_NEW_PAYEES_24H(ReasonCode.MANY_NEW_PAYEES, v -> v >= 2),
    DRAIN_RATIO(ReasonCode.ACCOUNT_DRAIN, v -> v >= 0.5),
    DEVICE_AGE_HOURS(ReasonCode.NEW_DEVICE, v -> v < 48),
    SIM_CHANGE_HOURS(ReasonCode.SIM_SWAP, v -> v < 72),
    REMOTE_ACCESS(ReasonCode.REMOTE_ACCESS, v -> v >= 1),
    ON_CALL(ReasonCode.ON_CALL, v -> v >= 1),
    PAYEE_MULE_SCORE(ReasonCode.PAYEE_MULE_SUSPECT, v -> v >= 0.4),
    PAYEE_AGE_DAYS(ReasonCode.YOUNG_PAYEE_ACCOUNT, v -> v < 30),
    PAYEE_SENDERS_24H(ReasonCode.PAYEE_FAN_IN, v -> v >= 5),
    PAYEE_REPORTS_30D(ReasonCode.PAYEE_REPORTED, v -> v >= 1),
    PAYEE_INTEL(ReasonCode.PAYEE_INTEL, v -> v >= 0.5),
    PAYEE_NAME_MISMATCH(ReasonCode.NAME_MISMATCH, v -> v >= 0.5),
    NIGHT(ReasonCode.NIGHT, v -> v >= 1),
    PAYER_AGE_DAYS(ReasonCode.NEW_PAYER_ACCOUNT, v -> v < 30),
    P2P(null, v -> false);

    public static final List<String> NAMES = Arrays.stream(values()).map(f -> f.name().toLowerCase()).toList();
    public static final int COUNT = values().length;

    private final ReasonCode reason;
    private final DoublePredicate reportable;

    PaymentFeature(ReasonCode reason, DoublePredicate reportable) {
        this.reason = reason;
        this.reportable = reportable;
    }

    public ReasonCode reason() {
        return reason;
    }

    /** The value is on the risky side (NaN, an unknown value, never is). */
    public boolean reportable(double value) {
        return reason != null && !Double.isNaN(value) && reportable.test(value);
    }
}
