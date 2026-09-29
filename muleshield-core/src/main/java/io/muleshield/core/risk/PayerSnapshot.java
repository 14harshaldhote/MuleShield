package io.muleshield.core.risk;

import java.time.Instant;

import io.muleshield.core.money.Money;

/**
 * What the paying bank knows about the payer at the moment of the payment, read from the feature
 * store in one round trip.
 *
 * @param logAmountMean  mean of log10(scaled amount) over past payments (exponentially weighted)
 * @param logAmountStd   its standard deviation; small histories fall back to a population prior
 * @param knowsPayee     the payer has paid this payee before
 * @param payeeTrusted   the payer added this payee to their trusted list (bypasses RBI's cooling window)
 */
public record PayerSnapshot(
        Instant accountOpenedAt,
        Money availableBalance,
        int txCount1h,
        Money txAmount1h,
        int txCount24h,
        int newPayees24h,
        double logAmountMean,
        double logAmountStd,
        int historyCount,
        boolean knowsPayee,
        boolean payeeTrusted,
        boolean killSwitchOn) {
}
