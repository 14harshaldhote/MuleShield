package io.muleshield.core.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.muleshield.core.payment.PaymentType;
import io.muleshield.core.payment.Scheme;

/**
 * A payment's outcome, published once per state change through the payments-service outbox.
 * Consumers must be idempotent on {@code (uetr, status)}: delivery is at least once.
 *
 * @param deviceAccounts accounts used from the payer's device in 30 days (from the risk decision)
 * @param payerMuleScore the payer's own mule score when it paid; lets the payee's detector see
 *                       money arriving from a suspected mule (layered chains)
 * @param features       the model inputs at decision time, logged for the next training run
 */
public record PaymentEvent(
        UUID uetr,
        Status status,
        String debtor,
        String creditor,
        long amountMinor,
        String currency,
        PaymentType type,
        Scheme scheme,
        Instant at,
        Instant debtorOpenedAt,
        Instant creditorOpenedAt,
        int deviceAccounts,
        double payerMuleScore,
        double score,
        String action,
        List<String> reasons,
        String modelVersion,
        String packId,
        double[] features) {

    public enum Status { EXECUTED, HELD, DECLINED, CANCELLED, AWAITING_STEP_UP }
}
