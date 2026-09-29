package io.muleshield.risk.stream;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import io.muleshield.core.events.AccountFlag;
import io.muleshield.core.events.HoldEvent;
import io.muleshield.core.events.PaymentEvent;
import io.muleshield.core.events.ScamReport;
import io.muleshield.core.events.Topics;
import io.muleshield.core.mule.MuleEngine;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.state.AccountEvent;
import io.muleshield.core.state.AccountState;
import io.muleshield.risk.config.MuleShieldProperties;
import io.muleshield.risk.store.FeatureStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * <pre>
 *  payments.events ──(executed: split into debit + credit legs)──┐
 *  fraud.reports   ──(scam report on one of our accounts)───────┼─► repartition by account ─► AccountProcessor ─► risk.account-flags
 *  cases.holds     ──(hold placed / confirmed / released)───────┘        (RocksDB state + changelog, Valkey write-through)
 * </pre>
 * Only this bank's accounts get state: another bank's customer is that bank's to monitor.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafkaStreams
public class AccountTopology {

    static final String STORE = "account-state";

    @Autowired
    void build(StreamsBuilder builder, JsonMapper json, MuleEngine engine, PolicyPack pack, FeatureStore features,
               MuleShieldProperties props) {
        String ours = props.bankId() + ":";
        var commands = new JsonSerde<>(json, AccountCommand.class);
        builder.addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(STORE), Serdes.String(),
                new JsonSerde<>(json, AccountState.class)));

        KStream<String, AccountCommand> legs = builder
                .stream(Topics.PAYMENTS, Consumed.with(Serdes.String(), new JsonSerde<>(json, PaymentEvent.class)))
                .filter((k, e) -> e != null && e.status() == PaymentEvent.Status.EXECUTED)
                .flatMap((k, e) -> {
                    List<KeyValue<String, AccountCommand>> out = new ArrayList<>(2);
                    for (AccountEvent leg : AccountEvent.of(e)) {
                        if (leg.account().startsWith(ours)) {
                            out.add(KeyValue.pair(leg.account(), AccountCommand.leg(leg)));
                        }
                    }
                    return out;
                });
        KStream<String, AccountCommand> reports = builder
                .stream(Topics.REPORTS, Consumed.with(Serdes.String(), new JsonSerde<>(json, ScamReport.class)))
                .filter((k, r) -> r != null && r.reportedAccount().startsWith(ours))
                .map((k, r) -> KeyValue.pair(r.reportedAccount(), AccountCommand.report(r.reportedAccount(), r.reportedAt())));
        KStream<String, AccountCommand> holds = builder
                .stream(Topics.HOLDS, Consumed.with(Serdes.String(), new JsonSerde<>(json, HoldEvent.class)))
                .filter((k, h) -> h != null && h.account().startsWith(ours))
                .map((k, h) -> KeyValue.pair(h.account(), AccountCommand.hold(h)));

        legs.merge(reports).merge(holds)
                .repartition(Repartitioned.with(Serdes.String(), commands).withName("by-account"))
                .process(() -> new AccountProcessor(engine, pack, features), STORE)
                .to(Topics.ACCOUNT_FLAGS, Produced.with(Serdes.String(), new JsonSerde<>(json, AccountFlag.class)));
    }
}
