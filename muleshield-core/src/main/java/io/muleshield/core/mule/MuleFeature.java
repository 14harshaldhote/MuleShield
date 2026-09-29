package io.muleshield.core.mule;

import java.util.Arrays;
import java.util.List;
import java.util.function.DoublePredicate;

import io.muleshield.core.risk.ReasonCode;

/**
 * The mule model's inputs: how money moves through an account in the last 24 hours. Computed by
 * {@link MuleFeatures} for training and serving alike.
 */
public enum MuleFeature {
    ACCOUNT_AGE_DAYS(ReasonCode.MULE_YOUNG_ACCOUNT, v -> v < 30),
    IN_COUNT_24H(ReasonCode.MULE_VOLUME, v -> v >= 10),
    DISTINCT_SENDERS_24H(ReasonCode.MULE_FAN_IN, v -> v >= 4),
    NEW_SENDER_SHARE(ReasonCode.MULE_FAN_IN, v -> v >= 0.6),
    IN_AMOUNT_24H_LOG(ReasonCode.MULE_VOLUME, v -> v >= Math.log10(3)),
    OUT_IN_RATIO(ReasonCode.MULE_PASS_THROUGH, v -> v >= 0.8),
    PASS_THROUGH_1H_SHARE(ReasonCode.MULE_PASS_THROUGH, v -> v >= 0.5),
    MEDIAN_DWELL_LOG(ReasonCode.MULE_PASS_THROUGH, v -> v < Math.log10(61)),
    DISTINCT_BENEFICIARIES_24H(ReasonCode.MULE_FAN_OUT, v -> v >= 3),
    CASH_OUT_SHARE(ReasonCode.MULE_CASH_OUT, v -> v >= 0.3),
    UPSTREAM_RISK(ReasonCode.MULE_UPSTREAM, v -> v >= 0.4),
    DEVICE_ACCOUNTS(ReasonCode.MULE_SHARED_DEVICE, v -> v >= 3),
    REPORTS_30D(ReasonCode.PAYEE_REPORTED, v -> v >= 1),
    INTEL(ReasonCode.PAYEE_INTEL, v -> v >= 0.5),
    NIGHT_SHARE(ReasonCode.NIGHT, v -> v >= 0.5),
    DORMANT_GAP_DAYS(ReasonCode.MULE_DORMANT_REACTIVATED, v -> v >= 60);

    public static final List<String> NAMES = Arrays.stream(values()).map(f -> f.name().toLowerCase()).toList();
    public static final int COUNT = values().length;

    private final ReasonCode reason;
    private final DoublePredicate reportable;

    MuleFeature(ReasonCode reason, DoublePredicate reportable) {
        this.reason = reason;
        this.reportable = reportable;
    }

    public ReasonCode reason() {
        return reason;
    }

    public boolean reportable(double value) {
        return !Double.isNaN(value) && reportable.test(value);
    }
}
