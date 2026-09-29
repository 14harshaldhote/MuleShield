package io.muleshield.risk.api;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.muleshield.core.intel.Pseudonymizer;
import io.muleshield.core.payee.NameMatcher;
import io.muleshield.core.payment.PaymentInstruction;
import io.muleshield.core.risk.PayeeSnapshot;
import io.muleshield.core.risk.PayerSnapshot;
import io.muleshield.core.risk.PaymentContext;
import io.muleshield.core.risk.RiskDecision;
import io.muleshield.core.risk.RiskEngine;
import io.muleshield.core.state.AccountState;
import io.muleshield.risk.config.MuleShieldProperties;
import io.muleshield.risk.store.FeatureStore;

/** One payment decision: one feature-store round trip, then the pure engine. */
@Service
public class AssessmentService {

    private final RiskEngine engine;
    private final FeatureStore store;
    private final Pseudonymizer pseudonymizer;
    private final Clock clock;
    private final String bankId;
    private final Timer timer;
    private final MeterRegistry meters;

    public AssessmentService(RiskEngine engine, FeatureStore store, Pseudonymizer pseudonymizer, Clock clock,
                             MuleShieldProperties props, MeterRegistry meters) {
        this.engine = engine;
        this.store = store;
        this.pseudonymizer = pseudonymizer;
        this.clock = clock;
        this.bankId = props.bankId();
        this.meters = meters;
        this.timer = Timer.builder("muleshield.assessment").description("Payment decision latency, feature store included")
                .publishPercentiles(0.5, 0.99, 0.999).register(meters);
    }

    public AssessmentResponse assess(AssessmentRequest req) {
        long t0 = System.nanoTime();
        Instant now = clock.instant();
        PaymentInstruction p = req.payment();
        String payer = p.debtor().account().key();
        String payee = p.creditor().account().key();
        String device = p.debtor().account().bankId() + "|" + req.session().deviceId();

        FeatureStore.Lookup l = store.lookup(payer, payee, device, pseudonymizer.lookupTokens(p.creditor().account(), now), now);
        AccountState payerState = l.payer() != null ? l.payer() : new AccountState(payer, null);
        PayerSnapshot payerSnap = payerState.payerSnapshot(now, req.availableBalance(), payee, req.payeeTrusted(), req.killSwitchOn());
        PayeeSnapshot payeeSnap = payeeSnapshot(payee, l, now);
        NameMatcher.Check check = req.payeeCheck() != null ? req.payeeCheck()
                : new NameMatcher.Check(NameMatcher.Result.NOT_POSSIBLE, null, 0);

        RiskDecision d = engine.assess(new PaymentContext(p, req.session(), payerSnap, payeeSnap, check, now));
        long nanos = System.nanoTime() - t0;
        timer.record(nanos, TimeUnit.NANOSECONDS);
        meters.counter("muleshield.decisions", "action", d.action().name(), "stepUp", String.valueOf(d.stepUp())).increment();
        return new AssessmentResponse(d, l.deviceAccounts(), payerState.muleScore(), nanos / 1000);
    }

    private PayeeSnapshot payeeSnapshot(String payee, FeatureStore.Lookup l, Instant now) {
        if (payee.startsWith(bankId + ":") && l.payee() != null) {
            PayeeSnapshot s = l.payee().payeeSnapshot(now, l.intel());
            return new PayeeSnapshot(s.accountOpenedAt(), s.muleScore(), s.onDebitHold(), s.inCount24h(), s.distinctSenders24h(),
                    Math.max(s.scamReports30d(), l.payeeReports()), l.intel());
        }
        // Another bank's account: all we can know is what victims reported and what that bank shared.
        // A confirmed-mule signal from its own bank means the account is frozen there: treat it as held.
        return new PayeeSnapshot(null, 0, l.intel() >= 0.99, 0, 0, l.payeeReports(), l.intel());
    }
}
