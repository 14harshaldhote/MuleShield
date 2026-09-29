package io.muleshield.core.mule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.muleshield.core.payment.PaymentType;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.policy.PolicyPacks;
import io.muleshield.core.risk.Reason;
import io.muleshield.core.risk.ReasonCode;
import io.muleshield.core.state.AccountEvent;
import io.muleshield.core.state.AccountState;

class MuleEngineTest {

    private final PolicyPack pack = PolicyPacks.load("IN-RBI-2026");
    private final MuleEngine engine = new MuleEngine(pack, new MuleRuleScorer());
    private final Instant t0 = Instant.parse("2026-10-01T05:00:00Z");

    @Test
    void aFirstLayerMuleIsFlaggedWithReasons() {
        AccountState s = new AccountState("B:mule", t0.minus(Duration.ofDays(12)));
        for (int i = 0; i < 5; i++) {
            s.apply(new AccountEvent(AccountEvent.Kind.CREDIT, "B:mule", t0.plus(Duration.ofMinutes(20L * i)), 3_000_000,
                    "V:victim" + i, PaymentType.P2P, 0, 0, null), pack);
            s.apply(new AccountEvent(AccountEvent.Kind.DEBIT, "B:mule", t0.plus(Duration.ofMinutes(20L * i + 8)), 2_900_000,
                    "C:layer2-" + (i % 3), PaymentType.P2P, 0, 1, null), pack);
        }
        MuleAssessment a = engine.assess(s, t0.plus(Duration.ofHours(2)), 0);
        assertThat(a.action()).isNotEqualTo(MuleAssessment.Action.NONE);
        assertThat(a.reasons()).extracting(Reason::code).contains(ReasonCode.MULE_PASS_THROUGH, ReasonCode.MULE_FAN_IN);
    }

    @Test
    void aSalaryEarnerPayingBillsIsNot() {
        AccountState s = new AccountState("B:worker", t0.minus(Duration.ofDays(1500)));
        s.apply(new AccountEvent(AccountEvent.Kind.CREDIT, "B:worker", t0, 6_500_000, "B:employer", PaymentType.SALARY, 0, 0, null), pack);
        s.apply(new AccountEvent(AccountEvent.Kind.DEBIT, "B:worker", t0.plus(Duration.ofHours(3)), 1_800_000, "B:landlord", PaymentType.P2P, 0, 1, null), pack);
        s.apply(new AccountEvent(AccountEvent.Kind.DEBIT, "B:worker", t0.plus(Duration.ofHours(4)), 250_000, "B:power", PaymentType.BILL, 0, 1, null), pack);
        MuleAssessment a = engine.assess(s, t0.plus(Duration.ofHours(5)), 0);
        assertThat(a.action()).isEqualTo(MuleAssessment.Action.NONE);
        assertThat(a.score()).isLessThan(0.1);
    }

    @Test
    void anotherBanksConfirmedMuleSignalAndScamReportsTipItOver() {
        AccountState s = new AccountState("B:quiet", t0.minus(Duration.ofDays(200)));
        s.apply(new AccountEvent(AccountEvent.Kind.CREDIT, "B:quiet", t0, 5_000_000, "V:a", PaymentType.P2P, 0, 0, null), pack);
        s.apply(AccountEvent.scamReport("B:quiet", t0.plusSeconds(3600)), pack);
        s.apply(AccountEvent.scamReport("B:quiet", t0.plusSeconds(7200)), pack);
        s.apply(AccountEvent.scamReport("B:quiet", t0.plusSeconds(9000)), pack);
        MuleAssessment a = engine.assess(s, t0.plusSeconds(9100), 1.0);
        assertThat(a.action()).isEqualTo(MuleAssessment.Action.FLAG);
    }
}
