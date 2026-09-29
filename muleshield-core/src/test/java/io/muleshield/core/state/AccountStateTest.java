package io.muleshield.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.muleshield.core.money.Money;
import io.muleshield.core.payment.PaymentType;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.policy.PolicyPacks;
import tools.jackson.databind.json.JsonMapper;

class AccountStateTest {

    private final PolicyPack pack = PolicyPacks.load("IN-RBI-2026");
    private final Instant t0 = Instant.parse("2026-10-01T04:30:00Z");

    private static AccountEvent credit(String account, Instant at, long rupees, String from) {
        return new AccountEvent(AccountEvent.Kind.CREDIT, account, at, rupees * 100, from, PaymentType.P2P, 0, 0, null);
    }

    private static AccountEvent debit(String account, Instant at, long rupees, String to, PaymentType type) {
        return new AccountEvent(AccountEvent.Kind.DEBIT, account, at, rupees * 100, to, type, 0, 1, null);
    }

    @Test
    void measuresHowFastInboundMoneyLeaves() {
        AccountState s = new AccountState("B:mule", t0.minus(Duration.ofDays(10)));
        s.apply(credit("B:mule", t0, 40_000, "B:v1"), pack);
        s.apply(credit("B:mule", t0.plusSeconds(600), 60_000, "B:v2"), pack);
        s.apply(debit("B:mule", t0.plusSeconds(1200), 90_000, "B:l2", PaymentType.P2P), pack);
        s.apply(debit("B:mule", t0.plus(Duration.ofHours(5)), 10_000, "ATM", PaymentType.CASH_OUT), pack);

        var f = s.flows(t0.plus(Duration.ofHours(6)), pack);
        assertThat(f.inCount()).isEqualTo(2);
        assertThat(f.distinctSenders()).isEqualTo(2);
        assertThat(f.newSenderShare()).isEqualTo(1.0);
        assertThat(f.fastShare()).isCloseTo(0.9, within(1e-9));
        assertThat(f.medianDwellMinutes()).isCloseTo(10.0, within(1e-9));
        assertThat(f.cashOutShare()).isCloseTo(0.1, within(1e-9));
        assertThat(f.inScaled()).isCloseTo(4.0, within(1e-9));
    }

    @Test
    void payerSnapshotCountsTheLastHourAndRemembersPayees() {
        AccountState s = new AccountState("A:payer", t0.minus(Duration.ofDays(900)));
        for (int i = 0; i < 12; i++) {
            s.apply(debit("A:payer", t0.minus(Duration.ofDays(12 - i)), 2_000, "A:grocer", PaymentType.P2M), pack);
        }
        s.apply(debit("A:payer", t0.minusSeconds(1800), 5_000, "B:new1", PaymentType.P2P), pack);

        var snap = s.payerSnapshot(t0, Money.of("50000", "INR"), "B:new2", false, false);
        assertThat(snap.txCount1h()).isEqualTo(1);
        assertThat(snap.txAmount1h()).isEqualTo(Money.of("5000", "INR"));
        assertThat(snap.newPayees24h()).isEqualTo(1);
        assertThat(snap.knowsPayee()).isFalse();
        assertThat(s.knowsPayee("A:grocer")).isTrue();
        assertThat(snap.historyCount()).isEqualTo(13);
        assertThat(snap.logAmountMean()).isBetween(Math.log10(0.08), Math.log10(0.2));
    }

    @Test
    void survivesAJsonRoundTripAsAStateStoreValue() {
        AccountState s = new AccountState("B:x", t0.minus(Duration.ofDays(3)));
        s.apply(credit("B:x", t0, 1_000, "B:y"), pack);
        s.apply(debit("B:x", t0.plusSeconds(60), 900, "B:z", PaymentType.P2P), pack);
        s.setStatus(AccountState.Status.FLAGGED);

        JsonMapper json = JsonMapper.builder().build();
        AccountState copy = json.readValue(json.writeValueAsBytes(s), AccountState.class);
        assertThat(copy.flows(t0.plusSeconds(120), pack)).isEqualTo(s.flows(t0.plusSeconds(120), pack));
        assertThat(copy.status()).isEqualTo(AccountState.Status.FLAGGED);
        assertThat(copy.knowsPayee("B:z")).isTrue();
    }
}
