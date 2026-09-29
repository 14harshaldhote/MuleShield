package io.muleshield.sim;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * One simulation run.
 *
 * @param scamRate          multiplier on the base scam rates; fraud is oversampled (base rates are
 *                          several times real-world incidence) so a run has enough victims for
 *                          stable metrics, and the report says so
 * @param adaptiveAdversary scammers who have adapted to today's controls: they split payments under
 *                          the ₹10,000 delay threshold, have victims whitelist the payee, and use
 *                          mules that wait hours before forwarding
 * @param warmupDays        days that build history and are left out of every metric and training row
 * @param trainingOut       where to write feature rows for model training, or null
 */
public record SimConfig(
        String packId,
        long seed,
        int customers,
        int days,
        LocalDate start,
        Strategy strategy,
        double scamRate,
        boolean adaptiveAdversary,
        int warmupDays,
        Assumptions assumptions,
        Path paymentModel,
        Path muleModel,
        Path trainingOut) {
}
