package io.muleshield.core.policy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import io.muleshield.core.money.Money;
import io.muleshield.core.payment.PaymentType;

/**
 * One jurisdiction's scam and mule rules, as data. The engine is country-neutral; a bank switches
 * countries by loading a different pack, not by changing code. Every rule carries the legal
 * reference it implements and whether that reference is in force, a draft, or agreed but not yet
 * applicable, so nothing here overstates the law.
 * <p>
 * Packs shipped: {@code IN-RBI-2026}, {@code UK-PSR-2024}, {@code EU-IPR-2025}, {@code SG-SRF-2024},
 * {@code AU-SPF-2025} (see {@code src/main/resources/policy-packs}).
 */
public record PolicyPack(
        String id,
        String jurisdiction,
        String name,
        String currency,
        BigDecimal amountScale,
        ZoneId zone,
        Thresholds thresholds,
        ProtectiveDelay protectiveDelay,
        RiskHold riskHold,
        PayeeVerification payeeVerification,
        DebitHold debitHold,
        CreditCap creditCap,
        NewDeviceCooling newDeviceCooling,
        DrainProtection drainProtection,
        StepUp stepUp,
        Reimbursement reimbursement,
        List<LocalDate> holidays,
        List<Reference> references) {

    public PolicyPack {
        holidays = holidays == null ? List.of() : List.copyOf(holidays);
    }

    /** The same pack with the blanket protective delay switched off: for what-if analysis of risk-based holds alone. */
    public PolicyPack withoutBlanketDelay() {
        var d = protectiveDelay;
        return new PolicyPack(id, jurisdiction, name, currency, amountScale, zone, thresholds,
                new ProtectiveDelay(false, d.minAmount(), d.duration(), d.paymentTypes(), d.cancellable(), d.trustedPayeeBypass(), d.ref()),
                riskHold, payeeVerification, debitHold, creditCap, newDeviceCooling, drainProtection, stepUp, reimbursement,
                holidays, references);
    }

    /**
     * Amounts go into the model relative to {@code amountScale} (typical monthly take-home pay in this
     * market), so one model serves ₹, £, €, S$ and A$ without learning each currency.
     */
    public double scaled(Money amount) {
        return amount.major() / amountScale.doubleValue();
    }

    /** Model score cut-offs (0..1). */
    public record Thresholds(double stepUp, double hold, double block, double muleFlag, double muleAutoHold) {
    }

    /**
     * A blanket, rule-based delay on push payments at or above an amount, cancellable by the payer
     * (RBI's April 2026 draft proposes one hour for transfers of ₹10,000 or more).
     */
    public record ProtectiveDelay(boolean enabled, BigDecimal minAmount, Duration duration, Set<PaymentType> paymentTypes,
                                  boolean cancellable, boolean trustedPayeeBypass, String ref) {

        public Money minAmount(String currency) {
            return Money.of(minAmount, currency);
        }
    }

    /**
     * A risk-based delay for payments the model scores high: the UK allows up to the end of the fourth
     * business day, Singapore's framework a 24-hour hold on account drains.
     */
    public record RiskHold(boolean enabled, Duration duration, int businessDays, boolean mustInformPayer, String ref) {
    }

    public record PayeeVerification(Mode mode, boolean beforeExecution, String ref) {
        public enum Mode { NONE, NAME_DISPLAY, CONFIRMATION_OF_PAYEE, VERIFICATION_OF_PAYEE }
    }

    /** A temporary debit hold on a suspected mule account and its deadlines (RBI draft SOP, Sep 2026). */
    public record DebitHold(boolean enabled, Duration maxDuration, Duration customerResponseWindow,
                            Duration bankDecisionWindow, String ref) {
    }

    /** Aggregate annual credits above which new credits are held as "shadow credits" (RBI draft, Apr 2026). */
    public record CreditCap(boolean enabled, BigDecimal annualLimit, String ref) {
    }

    /** No high-risk payments for a while after a new device or security token is activated (Singapore SRF: 12 h). */
    public record NewDeviceCooling(boolean enabled, Duration duration, String ref) {
    }

    /** Detects a fast drain of the account and holds it (Singapore SRF duty 5). */
    public record DrainProtection(boolean enabled, double balanceShare, Duration window, String ref) {
    }

    /** How a risky payment is re-authorised: a passkey signing the exact amount and payee. */
    public record StepUp(String method, boolean dynamicLinking, String ref) {
    }

    public record Reimbursement(boolean mandatory, BigDecimal maxAmount, double sendingBankShare, String ref) {
    }

    public record Reference(String key, String title, Status status, String effective, String url) {
        public enum Status { IN_FORCE, DRAFT, NOT_YET_APPLICABLE, INDUSTRY, BANK_POLICY }
    }
}
