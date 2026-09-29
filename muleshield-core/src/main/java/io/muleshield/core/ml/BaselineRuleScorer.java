package io.muleshield.core.ml;

import static io.muleshield.core.risk.PaymentFeature.*;

import io.muleshield.core.risk.PaymentFeature;

/**
 * Hand-written scam rules, expressed in the same log-odds form as the model so the engine, the
 * explanations and the evaluation treat both alike. It is the fallback when no trained model is
 * loaded, and the baseline the model has to beat.
 * <p>
 * The weights encode the typologies in public fraud reports (UK Finance, I4C, FATF), not fitted
 * values; the simulator measures how well they do.
 */
public final class BaselineRuleScorer implements Scorer {

    static final double BIAS = -5.0;

    @Override
    public String version() {
        return "rules-v1";
    }

    @Override
    public Score score(double[] x) {
        double[] c = new double[PaymentFeature.COUNT];
        boolean newPayee = v(x, PAYEE_NEW) >= 1;
        boolean large = v(x, AMOUNT_SCALED_LOG) >= Math.log10(0.4);

        double z = v(x, AMOUNT_Z);
        add(c, AMOUNT_Z, (z > 2 ? 1.0 : 0) + (z > 3 ? 0.8 : 0));
        add(c, PAYEE_NEW, newPayee ? 0.6 : 0);
        add(c, AMOUNT_SCALED_LOG, newPayee && large ? 1.2 : 0);
        add(c, PAYER_TX_1H, v(x, PAYER_TX_1H) >= 3 ? 0.8 : 0);
        add(c, PAYER_NEW_PAYEES_24H, v(x, PAYER_NEW_PAYEES_24H) >= 2 ? 0.8 : 0);
        double drain = v(x, DRAIN_RATIO);
        add(c, DRAIN_RATIO, (drain >= 0.5 ? 1.0 : 0) + (drain >= 0.9 ? 0.8 : 0));
        add(c, DEVICE_AGE_HOURS, v(x, DEVICE_AGE_HOURS) < 48 ? 1.2 : 0);
        // A fresh SIM plus a new payee is the classic SIM-swap takeover; a fresh SIM alone is often just a new phone.
        add(c, SIM_CHANGE_HOURS, v(x, SIM_CHANGE_HOURS) < 72 ? (newPayee ? 3.5 : 2.5) : 0);
        add(c, REMOTE_ACCESS, v(x, REMOTE_ACCESS) >= 1 ? 2.2 : 0);
        // A live call during a large first payment is the signature of "digital arrest" and bank-impersonation scams.
        add(c, ON_CALL, v(x, ON_CALL) >= 1 ? (newPayee && large ? 2.0 : 1.0) : 0);
        add(c, PAYEE_MULE_SCORE, 4.0 * v(x, PAYEE_MULE_SCORE));
        add(c, PAYEE_AGE_DAYS, v(x, PAYEE_AGE_DAYS) < 30 ? 0.8 : 0);
        add(c, PAYEE_SENDERS_24H, v(x, PAYEE_SENDERS_24H) >= 5 ? 0.8 : 0);
        add(c, PAYEE_REPORTS_30D, v(x, PAYEE_REPORTS_30D) >= 1 ? 2.5 : 0);
        add(c, PAYEE_INTEL, 2.5 * v(x, PAYEE_INTEL));
        add(c, PAYEE_NAME_MISMATCH, 1.5 * v(x, PAYEE_NAME_MISMATCH));
        add(c, NIGHT, v(x, NIGHT) >= 1 ? 0.4 : 0);
        add(c, P2P, v(x, P2P) >= 1 ? 0.3 : 0);

        double margin = BIAS;
        for (double contribution : c) {
            margin += contribution;
        }
        return new Score(Scorer.sigmoid(margin), BIAS, c);
    }

    /** NaN (unknown) compares false everywhere, so a missing value never adds risk; 0 keeps the arithmetic safe. */
    private static double v(double[] x, PaymentFeature f) {
        double value = x[f.ordinal()];
        return Double.isNaN(value) ? (f == DEVICE_AGE_HOURS || f == SIM_CHANGE_HOURS || f == PAYEE_AGE_DAYS ? Double.MAX_VALUE : 0) : value;
    }

    private static void add(double[] c, PaymentFeature f, double logOdds) {
        c[f.ordinal()] += logOdds;
    }
}
