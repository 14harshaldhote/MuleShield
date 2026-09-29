package io.muleshield.core.risk;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.muleshield.core.money.Money;

/**
 * The engine's answer for one payment: what to do, why, and what to tell the payer.
 *
 * @param stepUp                 re-authorise with a passkey whose signature covers this payment's
 *                               amount and payee (dynamic linking)
 * @param holdUntil              for {@link Action#HOLD}: the payment is released at this instant unless
 *                               cancelled or refused first
 * @param reviewRequired         for a hold: an analyst or the payer must act before release (a risk hold);
 *                               false for a blanket protective delay that simply runs out
 * @param customerMessage        the most relevant scam-specific warning, or null
 * @param reimbursementExposure  what the sending bank would owe if this is a scam and goes through
 *                               (zero where reimbursement isn't mandatory)
 * @param features               the model inputs, logged so the next model trains on what serving saw
 */
public record RiskDecision(
        UUID uetr,
        Action action,
        boolean stepUp,
        Instant holdUntil,
        boolean cancellable,
        boolean reviewRequired,
        double score,
        List<Reason> reasons,
        String customerMessage,
        Money reimbursementExposure,
        String modelVersion,
        String policyPackId,
        double[] features) {

    public RiskDecision {
        reasons = List.copyOf(reasons);
    }

    public boolean has(ReasonCode code) {
        return reasons.stream().anyMatch(r -> r.code() == code);
    }
}
