package io.muleshield.core.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.muleshield.core.money.Money;
import io.muleshield.core.payee.NameMatcher;
import io.muleshield.core.payment.AccountRef;
import io.muleshield.core.payment.Party;
import io.muleshield.core.payment.PaymentInstruction;
import io.muleshield.core.payment.PaymentType;
import io.muleshield.core.payment.Scheme;
import io.muleshield.core.payment.SessionSignals;

/** Builds payment contexts for tests: a long-standing customer paying an ordinary amount to someone they know. */
final class Payments {

    Instant now = Instant.parse("2026-10-01T10:30:00Z");
    String currency = "INR";
    Scheme scheme = Scheme.UPI;
    String amount = "2000";
    PaymentType type = PaymentType.P2P;
    String balance = "150000";
    boolean knowsPayee = true;
    boolean trusted = false;
    boolean killSwitch = false;
    int tx1h = 0;
    String out1h = "0";
    SessionSignals session = SessionSignals.trustedDevice("dev-1", now.minus(Duration.ofDays(400)));
    PayeeSnapshot payee = new PayeeSnapshot(now.minus(Duration.ofDays(900)), 0.02, false, 1, 1, 0, 0);
    NameMatcher.Check nameCheck = new NameMatcher.Check(NameMatcher.Result.MATCH, "Asha Rao", 1.0);

    Payments in(String currency, Scheme scheme) {
        this.currency = currency;
        this.scheme = scheme;
        return this;
    }

    Payments amount(String amount) {
        this.amount = amount;
        return this;
    }

    Payments newPayee() {
        this.knowsPayee = false;
        this.payee = new PayeeSnapshot(null, 0, false, 0, 0, 0, 0);
        return this;
    }

    /** A "digital arrest": a caller posing as police keeps the victim on the line while they empty the account to a new payee. */
    Payments coachedOnCall() {
        newPayee();
        session = new SessionSignals("dev-1", now.minus(Duration.ofDays(400)), null, false, true);
        return this;
    }

    PaymentContext build() {
        Money money = Money.of(amount, currency);
        var payment = new PaymentInstruction(UUID.randomUUID(), "E2E-1",
                new Party("Payer", new AccountRef("BANKA", "111")), new Party("Asha Rao", new AccountRef("BANKB", "222")),
                money, scheme, type, "OTHR", now);
        // A customer whose usual payment is about a tenth of the amount scale, with years of history.
        var payer = new PayerSnapshot(now.minus(Duration.ofDays(2000)), Money.of(balance, currency), tx1h,
                Money.of(out1h, currency), tx1h + 2, knowsPayee ? 0 : 1, Math.log10(0.08), 0.35, 120,
                knowsPayee, trusted, killSwitch);
        return new PaymentContext(payment, session, payer, payee, nameCheck, now);
    }
}
