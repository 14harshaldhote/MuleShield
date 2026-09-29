package io.muleshield.risk.intel;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import io.muleshield.core.events.HoldEvent;
import io.muleshield.core.events.ScamReport;
import io.muleshield.core.events.Topics;
import io.muleshield.core.intel.Pseudonymizer;
import io.muleshield.core.intel.SharedSignal;
import io.muleshield.core.payment.AccountRef;
import io.muleshield.risk.config.MuleShieldProperties;
import io.muleshield.risk.store.FeatureStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bank's side of the cross-bank mule exchange. Outbound: when a case confirms or holds one of
 * our accounts, publish a pseudonymous signal (a keyed hash, never the account number); when it is
 * cleared, retract it. Inbound: keep other banks' signals by token, to be looked up when one of our
 * customers is about to pay that account.                                     [AU-SPF] [UK-ECCTA-2023]
 */
@Component
class IntelExchange {

    private static final Logger log = LoggerFactory.getLogger(IntelExchange.class);

    private final FeatureStore store;
    private final Pseudonymizer pseudonymizer;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final MuleShieldProperties props;
    private final Clock clock;

    IntelExchange(FeatureStore store, Pseudonymizer pseudonymizer, KafkaTemplate<String, String> kafka, JsonMapper json,
                  MuleShieldProperties props, Clock clock) {
        this.store = store;
        this.pseudonymizer = pseudonymizer;
        this.kafka = kafka;
        this.json = json;
        this.props = props;
        this.clock = clock;
    }

    @KafkaListener(topics = Topics.SHARED_SIGNALS, groupId = "risk-intel-inbound")
    void inbound(String value) {
        SharedSignal s = json.readValue(value, SharedSignal.class);
        if (props.bankId().equals(s.fromBank())) {
            return;
        }
        store.putIntel(s.token(), s.weight(), props.intelTtl());
        log.info("intel from {}: {} {}", s.fromBank(), s.kind(), s.token().substring(0, 8));
    }

    @KafkaListener(topics = Topics.REPORTS, groupId = "risk-reports")
    void report(String value) {
        ScamReport r = json.readValue(value, ScamReport.class);
        store.addReport(r.reportedAccount(), r.reportedAt(), r.reportId());
    }

    @KafkaListener(topics = Topics.HOLDS, groupId = "risk-intel-outbound")
    void hold(String value) {
        HoldEvent h = json.readValue(value, HoldEvent.class);
        if (!h.account().startsWith(props.bankId() + ":")) {
            return;
        }
        Instant now = clock.instant();
        String epoch = Pseudonymizer.epoch(now);
        SharedSignal.Kind kind = switch (h.status()) {
            case CONFIRMED_MULE -> SharedSignal.Kind.MULE_CONFIRMED;
            case ON_HOLD -> SharedSignal.Kind.MULE_SUSPECTED;
            case RELEASED -> SharedSignal.Kind.CLEARED;
        };
        double confidence = kind == SharedSignal.Kind.CLEARED ? 0 : 1;
        // Publish under this month's and last month's token, so a retraction reaches every copy.
        for (String token : pseudonymizer.lookupTokens(AccountRef.parse(h.account()), now)) {
            SharedSignal s = new SharedSignal(token, epoch, props.bankId(), kind, confidence, now);
            kafka.send(Topics.SHARED_SIGNALS, token, json.writeValueAsString(s));
        }
    }
}
