package io.muleshield.core.risk;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

import io.muleshield.core.payment.PaymentInstruction;
import io.muleshield.core.payment.PaymentType;
import io.muleshield.core.payment.SessionSignals;
import io.muleshield.core.policy.PolicyPack;

/**
 * Turns a {@link PaymentContext} into the model's feature vector. Pure and allocation-light: it
 * runs on every payment inside the latency budget.
 * <p>
 * Amounts enter relative to the policy pack's {@code amountScale}, and the unusualness of an
 * amount is measured against the payer's own history, so the features mean the same thing in
 * rupees, pounds or euros. Unknown values are NaN, which the model treats as missing.
 */
public final class PaymentFeatures {

    /** Population prior for a payer with little history: typical spread of log10 amounts. */
    static final double PRIOR_LOG_STD = 0.45;
    static final double CAP_HOURS = 720;
    static final double CAP_DAYS = 3650;

    private PaymentFeatures() {
    }

    public static double[] extract(PaymentContext ctx, PolicyPack pack) {
        PaymentInstruction p = ctx.payment();
        PayerSnapshot payer = ctx.payer();
        PayeeSnapshot payee = ctx.payee();
        SessionSignals s = ctx.session();
        Instant now = ctx.now();
        double[] x = new double[PaymentFeature.COUNT];

        double scaled = pack.scaled(p.amount());
        double logAmount = Math.log10(Math.max(scaled, 1e-4));
        x[PaymentFeature.AMOUNT_SCALED_LOG.ordinal()] = logAmount;

        // Blend the payer's own spread with the prior until there are ~10 payments of history.
        double weight = Math.min(payer.historyCount(), 10) / 10.0;
        double mean = payer.historyCount() == 0 ? Math.log10(0.02) : payer.logAmountMean();
        double std = weight * Math.max(payer.logAmountStd(), 0.1) + (1 - weight) * PRIOR_LOG_STD;
        x[PaymentFeature.AMOUNT_Z.ordinal()] = (logAmount - mean) / std;

        x[PaymentFeature.PAYEE_NEW.ordinal()] = payer.knowsPayee() ? 0 : 1;
        x[PaymentFeature.PAYER_TX_1H.ordinal()] = payer.txCount1h();
        x[PaymentFeature.PAYER_AMOUNT_1H_LOG.ordinal()] = Math.log10(1 + pack.scaled(payer.txAmount1h()));
        x[PaymentFeature.PAYER_NEW_PAYEES_24H.ordinal()] = payer.newPayees24h();

        double balance = payer.availableBalance().major();
        x[PaymentFeature.DRAIN_RATIO.ordinal()] = balance <= 0 ? 1.5 : Math.min(p.amount().major() / balance, 1.5);

        x[PaymentFeature.DEVICE_AGE_HOURS.ordinal()] = hoursSince(s.deviceBoundAt(), now);
        x[PaymentFeature.SIM_CHANGE_HOURS.ordinal()] = s.simChangedAt() == null ? CAP_HOURS : hoursSince(s.simChangedAt(), now);
        x[PaymentFeature.REMOTE_ACCESS.ordinal()] = s.remoteAccessActive() ? 1 : 0;
        x[PaymentFeature.ON_CALL.ordinal()] = s.onActiveCall() ? 1 : 0;

        x[PaymentFeature.PAYEE_MULE_SCORE.ordinal()] = payee.muleScore();
        x[PaymentFeature.PAYEE_AGE_DAYS.ordinal()] = payee.accountOpenedAt() == null ? Double.NaN
                : Math.min(Duration.between(payee.accountOpenedAt(), now).toHours() / 24.0, CAP_DAYS);
        x[PaymentFeature.PAYEE_SENDERS_24H.ordinal()] = payee.distinctSenders24h();
        x[PaymentFeature.PAYEE_REPORTS_30D.ordinal()] = payee.scamReports30d();
        x[PaymentFeature.PAYEE_INTEL.ordinal()] = payee.intelScore();
        x[PaymentFeature.PAYEE_NAME_MISMATCH.ordinal()] = ctx.payeeCheck() == null ? 0 : ctx.payeeCheck().mismatch();

        int hour = ZonedDateTime.ofInstant(now, pack.zone()).getHour();
        x[PaymentFeature.NIGHT.ordinal()] = hour >= 23 || hour < 6 ? 1 : 0;
        x[PaymentFeature.PAYER_AGE_DAYS.ordinal()] = payer.accountOpenedAt() == null ? Double.NaN
                : Math.min(Duration.between(payer.accountOpenedAt(), now).toHours() / 24.0, CAP_DAYS);
        x[PaymentFeature.P2P.ordinal()] = p.type() == PaymentType.P2P ? 1 : 0;
        return x;
    }

    private static double hoursSince(Instant then, Instant now) {
        if (then == null) {
            return CAP_HOURS;
        }
        return Math.max(0, Math.min(Duration.between(then, now).toMinutes() / 60.0, CAP_HOURS));
    }
}
