package io.muleshield.risk.api;

import io.muleshield.core.risk.RiskDecision;

/**
 * @param deviceAccounts accounts seen on the payer's device in 30 days (goes into the payment event)
 * @param payerMuleScore the payer's own mule score (goes into the payment event for the payee's detector)
 */
public record AssessmentResponse(RiskDecision decision, int deviceAccounts, double payerMuleScore, long latencyMicros) {
}
