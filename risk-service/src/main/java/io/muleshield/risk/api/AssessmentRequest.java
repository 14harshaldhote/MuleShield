package io.muleshield.risk.api;

import io.muleshield.core.money.Money;
import io.muleshield.core.payee.NameMatcher;
import io.muleshield.core.payment.PaymentInstruction;
import io.muleshield.core.payment.SessionSignals;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * What the paying bank's payment service sends for a decision. The payer's own settings (kill
 * switch, trusted payees) and balance are owned by the ledger and passed in; everything learned
 * from behaviour comes from the feature store.
 *
 * @param payeeCheck the outcome of the payee name check (CoP/VoP), or null where the scheme only displays the name
 */
public record AssessmentRequest(
        @NotNull @Valid PaymentInstruction payment,
        @NotNull SessionSignals session,
        @NotNull Money availableBalance,
        boolean payeeTrusted,
        boolean killSwitchOn,
        NameMatcher.Check payeeCheck) {
}
