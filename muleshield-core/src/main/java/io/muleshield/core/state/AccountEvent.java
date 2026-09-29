package io.muleshield.core.state;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import io.muleshield.core.events.PaymentEvent;
import io.muleshield.core.payment.PaymentInstruction;
import io.muleshield.core.payment.PaymentType;

/**
 * One thing that happened to one account. An executed payment becomes two: a DEBIT for the payer
 * and a CREDIT for the payee, each keyed by its own account so both sides' state is updated by
 * whoever owns that key (a Kafka Streams partition in the service, a map in the simulator).
 *
 * @param counterpartyRisk for a CREDIT, the sender's mule score when the payment was made, which
 *                         lets a second-layer mule be recognised by who pays it
 * @param deviceAccounts   for a DEBIT, how many accounts were used from the same device in 30 days
 */
public record AccountEvent(
        Kind kind,
        String account,
        Instant at,
        long amountMinor,
        String counterparty,
        PaymentType type,
        double counterpartyRisk,
        int deviceAccounts,
        Instant accountOpenedAt) {

    public enum Kind { CREDIT, DEBIT, SCAM_REPORT }

    public AccountEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(at, "at");
    }

    /** The two sides of an executed payment. */
    public static List<AccountEvent> of(PaymentInstruction p, Instant executedAt, double payerMuleScore, int deviceAccounts,
                                        Instant debtorOpenedAt, Instant creditorOpenedAt) {
        String debtor = p.debtor().account().key();
        String creditor = p.creditor().account().key();
        return List.of(
                new AccountEvent(Kind.DEBIT, debtor, executedAt, p.amount().minor(), creditor, p.type(), 0, deviceAccounts, debtorOpenedAt),
                new AccountEvent(Kind.CREDIT, creditor, executedAt, p.amount().minor(), debtor, p.type(), payerMuleScore, 0, creditorOpenedAt));
    }

    /** The two sides of an executed payment, from its published event. */
    public static List<AccountEvent> of(PaymentEvent e) {
        return List.of(
                new AccountEvent(Kind.DEBIT, e.debtor(), e.at(), e.amountMinor(), e.creditor(), e.type(), 0, e.deviceAccounts(), e.debtorOpenedAt()),
                new AccountEvent(Kind.CREDIT, e.creditor(), e.at(), e.amountMinor(), e.debtor(), e.type(), e.payerMuleScore(), 0, e.creditorOpenedAt()));
    }

    public static AccountEvent scamReport(String account, Instant at) {
        return new AccountEvent(Kind.SCAM_REPORT, account, at, 0, null, null, 0, 0, null);
    }
}
