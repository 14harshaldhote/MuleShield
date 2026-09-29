package io.muleshield.core.risk;

import java.time.Instant;

import io.muleshield.core.payee.NameMatcher;
import io.muleshield.core.payment.PaymentInstruction;
import io.muleshield.core.payment.SessionSignals;

/** Everything the engine looks at for one payment. */
public record PaymentContext(
        PaymentInstruction payment,
        SessionSignals session,
        PayerSnapshot payer,
        PayeeSnapshot payee,
        NameMatcher.Check payeeCheck,
        Instant now) {
}
