package io.muleshield.core.mule;

import java.time.Duration;
import java.time.Instant;

import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.state.AccountState;

/** Turns an account's recent flows into the mule model's feature vector. */
public final class MuleFeatures {

    private MuleFeatures() {
    }

    public static double[] extract(AccountState s, Instant now, PolicyPack pack, double intelScore) {
        AccountState.Flows f = s.flows(now, pack);
        double[] x = new double[MuleFeature.COUNT];
        x[MuleFeature.ACCOUNT_AGE_DAYS.ordinal()] = s.openedAt() == null ? Double.NaN
                : Math.min(Duration.between(s.openedAt(), now).toHours() / 24.0, 3650);
        x[MuleFeature.IN_COUNT_24H.ordinal()] = f.inCount();
        x[MuleFeature.DISTINCT_SENDERS_24H.ordinal()] = f.distinctSenders();
        x[MuleFeature.NEW_SENDER_SHARE.ordinal()] = f.newSenderShare();
        x[MuleFeature.IN_AMOUNT_24H_LOG.ordinal()] = Math.log10(1 + f.inScaled());
        x[MuleFeature.OUT_IN_RATIO.ordinal()] = f.inScaled() == 0 ? Double.NaN : Math.min(f.outScaled() / f.inScaled(), 2);
        x[MuleFeature.PASS_THROUGH_1H_SHARE.ordinal()] = f.fastShare();
        x[MuleFeature.MEDIAN_DWELL_LOG.ordinal()] = Double.isNaN(f.medianDwellMinutes()) ? Double.NaN
                : Math.log10(1 + Math.min(f.medianDwellMinutes(), 7 * 24 * 60));
        x[MuleFeature.DISTINCT_BENEFICIARIES_24H.ordinal()] = f.distinctBeneficiaries();
        x[MuleFeature.CASH_OUT_SHARE.ordinal()] = f.cashOutShare();
        x[MuleFeature.UPSTREAM_RISK.ordinal()] = f.upstreamRisk();
        x[MuleFeature.DEVICE_ACCOUNTS.ordinal()] = s.deviceAccounts();
        x[MuleFeature.REPORTS_30D.ordinal()] = s.reports30d(now);
        x[MuleFeature.INTEL.ordinal()] = intelScore;
        x[MuleFeature.NIGHT_SHARE.ordinal()] = f.nightShare();
        x[MuleFeature.DORMANT_GAP_DAYS.ordinal()] = Math.min(s.dormantGapDays(now), 365);
        return x;
    }
}
