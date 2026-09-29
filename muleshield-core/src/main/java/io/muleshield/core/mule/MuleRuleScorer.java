package io.muleshield.core.mule;

import static io.muleshield.core.mule.MuleFeature.*;

import io.muleshield.core.ml.Scorer;

/**
 * Mule typologies as hand-written log-odds rules (FATF, RBI and UK Finance mule indicators): the
 * fallback when no trained mule model is loaded, and the baseline it has to beat.
 */
public final class MuleRuleScorer implements Scorer {

    static final double BIAS = -6.0;

    @Override
    public String version() {
        return "mule-rules-v1";
    }

    @Override
    public Score score(double[] x) {
        double[] c = new double[MuleFeature.COUNT];
        double fast = v(x, PASS_THROUGH_1H_SHARE);
        c[PASS_THROUGH_1H_SHARE.ordinal()] = (fast >= 0.5 ? 1.5 : 0) + (fast >= 0.8 ? 1.0 : 0);
        c[OUT_IN_RATIO.ordinal()] = v(x, OUT_IN_RATIO) >= 0.8 ? 0.8 : 0;
        double dwell = x[MEDIAN_DWELL_LOG.ordinal()];
        c[MEDIAN_DWELL_LOG.ordinal()] = !Double.isNaN(dwell) && dwell < Math.log10(61) ? 0.8 : 0;
        double senders = v(x, DISTINCT_SENDERS_24H);
        c[DISTINCT_SENDERS_24H.ordinal()] = (senders >= 4 ? 1.0 : 0) + (senders >= 8 ? 0.8 : 0);
        c[NEW_SENDER_SHARE.ordinal()] = v(x, NEW_SENDER_SHARE) >= 0.6 && senders >= 3 ? 0.8 : 0;
        c[DISTINCT_BENEFICIARIES_24H.ordinal()] = v(x, DISTINCT_BENEFICIARIES_24H) >= 3 ? 0.8 : 0;
        c[CASH_OUT_SHARE.ordinal()] = v(x, CASH_OUT_SHARE) >= 0.3 ? 1.0 : 0;
        c[UPSTREAM_RISK.ordinal()] = 3.0 * v(x, UPSTREAM_RISK);
        c[DEVICE_ACCOUNTS.ordinal()] = v(x, DEVICE_ACCOUNTS) >= 3 ? 2.0 : 0;
        double reports = v(x, REPORTS_30D);
        c[REPORTS_30D.ordinal()] = (reports >= 1 ? 2.5 : 0) + (reports >= 3 ? 1.0 : 0);
        c[INTEL.ordinal()] = 2.5 * v(x, INTEL);
        double age = x[ACCOUNT_AGE_DAYS.ordinal()];
        c[ACCOUNT_AGE_DAYS.ordinal()] = !Double.isNaN(age) && age < 30 ? 0.8 : 0;
        boolean volume = v(x, IN_AMOUNT_24H_LOG) >= Math.log10(3);
        c[IN_AMOUNT_24H_LOG.ordinal()] = volume ? 0.6 : 0;
        c[DORMANT_GAP_DAYS.ordinal()] = v(x, DORMANT_GAP_DAYS) >= 60 && volume ? 1.2 : 0;
        c[NIGHT_SHARE.ordinal()] = v(x, NIGHT_SHARE) >= 0.5 ? 0.3 : 0;

        double margin = BIAS;
        for (double contribution : c) {
            margin += contribution;
        }
        return new Score(Scorer.sigmoid(margin), BIAS, c);
    }

    private static double v(double[] x, MuleFeature f) {
        double value = x[f.ordinal()];
        return Double.isNaN(value) ? 0 : value;
    }
}
