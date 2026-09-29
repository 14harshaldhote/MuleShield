package io.muleshield.core.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import io.muleshield.core.ml.BaselineRuleScorer;
import io.muleshield.core.money.Money;
import io.muleshield.core.payment.Scheme;
import io.muleshield.core.payment.SessionSignals;
import io.muleshield.core.policy.PolicyPacks;

/** The same engine and scorer, five countries' rules: each test is one rule from one pack. */
class RiskEngineTest {

    private final RiskEngine india = new RiskEngine(PolicyPacks.load("IN-RBI-2026"), new BaselineRuleScorer());
    private final RiskEngine uk = new RiskEngine(PolicyPacks.load("UK-PSR-2024"), new BaselineRuleScorer());
    private final RiskEngine eu = new RiskEngine(PolicyPacks.load("EU-IPR-2025"), new BaselineRuleScorer());
    private final RiskEngine sg = new RiskEngine(PolicyPacks.load("SG-SRF-2024"), new BaselineRuleScorer());

    @Test
    void anOrdinaryPaymentGoesThroughWithoutFriction() {
        RiskDecision d = india.assess(new Payments().build());
        assertThat(d.action()).isEqualTo(Action.ALLOW);
        assertThat(d.stepUp()).isFalse();
        assertThat(d.customerMessage()).isNull();
        assertThat(d.score()).isLessThan(0.05);
    }

    @Test
    void indiaHoldsLargePushPaymentsForAnHourUnlessThePayeeIsTrusted() {
        Payments p = new Payments().amount("15000");
        RiskDecision d = india.assess(p.build());
        assertThat(d.action()).isEqualTo(Action.HOLD);
        assertThat(d.cancellable()).isTrue();
        assertThat(d.reviewRequired()).isFalse();
        assertThat(d.holdUntil()).isEqualTo(p.now.plus(Duration.ofHours(1)));
        assertThat(d.has(ReasonCode.PROTECTIVE_DELAY)).isTrue();
        assertThat(d.reasons().getFirst().rule()).isEqualTo("RBI-APP-DRAFT-2026");

        p.trusted = true;
        assertThat(india.assess(p.build()).action()).isEqualTo(Action.ALLOW);
    }

    @Test
    void indiaDelayDoesNotApplyToMerchantPayments() {
        Payments p = new Payments().amount("15000");
        p.type = io.muleshield.core.payment.PaymentType.P2M;
        assertThat(india.assess(p.build()).action()).isEqualTo(Action.ALLOW);
    }

    @Test
    void aCoachedVictimEmptyingTheirAccountIsStoppedWithAScamSpecificWarning() {
        RiskDecision d = india.assess(new Payments().coachedOnCall().amount("140000").build());
        assertThat(d.action()).isEqualTo(Action.DECLINE);
        assertThat(d.has(ReasonCode.ON_CALL)).isTrue();
        assertThat(d.customerMessage()).contains("Police, courts and banks never ask you to move money");
    }

    @Test
    void theUkDelaysARiskyPaymentToTheEndOfTheFourthBusinessDay() {
        Payments p = new Payments().in("GBP", Scheme.FPS).newPayee().amount("1800");
        p.balance = "3000";
        p.now = ZonedDateTime.of(2026, 10, 1, 15, 0, 0, 0, ZoneId.of("Europe/London")).toInstant();
        p.session = new SessionSignals("dev-1", p.now.minus(Duration.ofDays(400)), null, true, false);
        RiskDecision d = uk.assess(p.build());
        assertThat(d.action()).isEqualTo(Action.HOLD);
        assertThat(d.reviewRequired()).isTrue();
        assertThat(d.holdUntil()).isEqualTo(ZonedDateTime.of(2026, 10, 8, 0, 0, 0, 0, ZoneId.of("Europe/London")).toInstant());
        assertThat(d.has(ReasonCode.REMOTE_ACCESS)).isTrue();
        // Mandatory reimbursement: half the loss falls on the sending bank.
        assertThat(d.reimbursementExposure()).isEqualTo(Money.of("900.00", "GBP"));
    }

    @Test
    void theEuCannotDelayAnInstantPaymentSoARiskyOneIsDeclined() {
        Payments p = new Payments().in("EUR", Scheme.SCT_INST).newPayee().amount("1800");
        p.balance = "3000";
        p.session = new SessionSignals("dev-1", p.now.minus(Duration.ofDays(400)), null, true, false);
        RiskDecision d = eu.assess(p.build());
        assertThat(d.action()).isEqualTo(Action.DECLINE);
        assertThat(d.holdUntil()).isNull();
    }

    @Test
    void singaporeBlocksNewPayeesDuringTheNewDeviceCoolingOff() {
        Payments p = new Payments().in("SGD", Scheme.FAST).newPayee().amount("300");
        p.session = SessionSignals.trustedDevice("dev-2", p.now.minus(Duration.ofHours(3)));
        RiskDecision d = sg.assess(p.build());
        assertThat(d.action()).isEqualTo(Action.DECLINE);
        assertThat(d.has(ReasonCode.NEW_DEVICE_COOLING)).isTrue();
    }

    @Test
    void singaporeHoldsAFastDrain() {
        Payments p = new Payments().in("SGD", Scheme.FAST).amount("2000");
        p.balance = "2500";
        p.tx1h = 2;
        p.out1h = "1500";
        RiskDecision d = sg.assess(p.build());
        assertThat(d.action()).isEqualTo(Action.HOLD);
        assertThat(d.has(ReasonCode.DRAIN_PROTECTION)).isTrue();
        assertThat(d.holdUntil()).isEqualTo(p.now.plus(Duration.ofHours(24)));
    }

    @Test
    void theKillSwitchAndAHeldPayeeStopEverything() {
        Payments p = new Payments();
        p.killSwitch = true;
        assertThat(india.assess(p.build()).reasons()).extracting(Reason::code).containsExactly(ReasonCode.KILL_SWITCH);

        Payments q = new Payments();
        q.payee = new PayeeSnapshot(Instant.parse("2026-09-20T00:00:00Z"), 0.97, true, 30, 25, 4, 0);
        RiskDecision d = india.assess(q.build());
        assertThat(d.action()).isEqualTo(Action.DECLINE);
        assertThat(d.reasons().getFirst().code()).isEqualTo(ReasonCode.PAYEE_ON_HOLD);
    }

    @Test
    void aFreshSimPayingSomeoneNewMustSignWithThePasskey() {
        Payments p = new Payments().amount("4000");
        p.session = new SessionSignals("dev-1", p.now.minus(Duration.ofDays(400)), p.now.minus(Duration.ofHours(20)), false, false);
        assertThat(india.assess(p.build()).stepUp()).as("known payee: a new phone, not a takeover").isFalse();

        RiskDecision d = india.assess(p.newPayee().build());
        assertThat(d.action()).isEqualTo(Action.ALLOW);
        assertThat(d.stepUp()).isTrue();
        assertThat(d.has(ReasonCode.SIM_SWAP)).isTrue();
        assertThat(d.customerMessage()).contains("SIM was changed");
    }
}
