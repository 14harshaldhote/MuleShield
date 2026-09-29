package io.muleshield.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.muleshield.core.payment.PaymentType;
import io.muleshield.core.policy.PolicyPack.PayeeVerification.Mode;
import io.muleshield.core.policy.PolicyPack.Reference.Status;

class PolicyPacksTest {

    @Test
    void everyBundledPackLoadsAndCitesItsRules() {
        var packs = PolicyPacks.all();
        assertThat(packs).containsOnlyKeys(PolicyPacks.BUNDLED);
        packs.values().forEach(p -> assertThat(p.references()).isNotEmpty());
    }

    @Test
    void indiaCarriesRbiDraftsMarkedAsDrafts() {
        PolicyPack in = PolicyPacks.load("IN-RBI-2026");
        assertThat(in.currency()).isEqualTo("INR");
        assertThat(in.protectiveDelay().minAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(in.protectiveDelay().duration()).isEqualTo(Duration.ofHours(1));
        assertThat(in.protectiveDelay().paymentTypes()).containsExactly(PaymentType.P2P);
        assertThat(in.debitHold().maxDuration()).isEqualTo(Duration.ofDays(60));
        assertThat(in.references()).filteredOn(r -> r.key().equals("RBI-APP-DRAFT-2026"))
                .singleElement().extracting(PolicyPack.Reference::status).isEqualTo(Status.DRAFT);
        assertThat(in.references()).filteredOn(r -> r.key().equals("RBI-AUTH-2025"))
                .singleElement().extracting(PolicyPack.Reference::status).isEqualTo(Status.IN_FORCE);
    }

    @Test
    void ukDelaysByBusinessDaysAndReimbursesUpTo85k() {
        PolicyPack uk = PolicyPacks.load("UK-PSR-2024");
        assertThat(uk.riskHold().businessDays()).isEqualTo(4);
        assertThat(uk.payeeVerification().mode()).isEqualTo(Mode.CONFIRMATION_OF_PAYEE);
        assertThat(uk.reimbursement().maxAmount()).isEqualByComparingTo(new BigDecimal("85000"));
        assertThat(uk.holidays()).contains(LocalDate.of(2026, 12, 28));
    }

    @Test
    void euDeclinesInsteadOfDelayingInstantPayments() {
        PolicyPack eu = PolicyPacks.load("EU-IPR-2025");
        assertThat(eu.riskHold().enabled()).isFalse();
        assertThat(eu.payeeVerification().mode()).isEqualTo(Mode.VERIFICATION_OF_PAYEE);
    }

    @Test
    void unknownPackIsRejected() {
        assertThatThrownBy(() -> PolicyPacks.load("XX-NONE")).hasMessageContaining("No policy pack");
    }
}
