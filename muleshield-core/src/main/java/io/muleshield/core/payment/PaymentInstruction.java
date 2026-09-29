package io.muleshield.core.payment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.muleshield.core.money.Money;

/**
 * A push payment, modelled on the ISO 20022 customer credit transfer (pacs.008 / pain.001):
 * UETR, end-to-end id, debtor, creditor, instructed amount, purpose. The same shape carries a UPI,
 * IMPS, UK Faster Payments, SEPA Instant, Singapore FAST or Australian NPP payment, which is what
 * keeps the engine independent of any one country's rails.                            [ISO20022]
 *
 * @param uetr        unique end-to-end transaction reference (UUID v4), the SWIFT gpi tracking key
 * @param endToEndId  the payer's own reference
 * @param type        what the payment is for; policy packs exempt some types (merchant, recurring)
 * @param purposeCode ISO 20022 ExternalPurpose1Code, e.g. SALA, GDDS, SUPP, OTHR
 */
public record PaymentInstruction(
        UUID uetr,
        String endToEndId,
        Party debtor,
        Party creditor,
        Money amount,
        Scheme scheme,
        PaymentType type,
        String purposeCode,
        Instant createdAt) {

    public PaymentInstruction {
        Objects.requireNonNull(uetr, "uetr");
        Objects.requireNonNull(debtor, "debtor");
        Objects.requireNonNull(creditor, "creditor");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        if (amount.minor() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (debtor.account().equals(creditor.account())) {
            throw new IllegalArgumentException("Debtor and creditor are the same account");
        }
    }
}
