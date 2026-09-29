package io.muleshield.risk.stream;

import java.util.List;

import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import io.muleshield.core.events.AccountFlag;
import io.muleshield.core.mule.MuleAssessment;
import io.muleshield.core.mule.MuleEngine;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.state.AccountState;
import io.muleshield.risk.store.FeatureStore;

/**
 * Owns the state of the accounts on its partitions: applies each movement of money, re-scores the
 * account for mule behaviour, and flags it the moment the score crosses the pack's threshold,
 * while the money may still be in it. The state lives in a RocksDB store backed by a changelog
 * topic, so a restarted instance resumes exactly where it stopped; each update is also written
 * through to Valkey for the payment path to read.
 */
final class AccountProcessor implements Processor<String, AccountCommand, String, AccountFlag> {

    private final MuleEngine engine;
    private final PolicyPack pack;
    private final FeatureStore features;
    private ProcessorContext<String, AccountFlag> context;
    private KeyValueStore<String, AccountState> store;

    AccountProcessor(MuleEngine engine, PolicyPack pack, FeatureStore features) {
        this.engine = engine;
        this.pack = pack;
        this.features = features;
    }

    @Override
    public void init(ProcessorContext<String, AccountFlag> context) {
        this.context = context;
        this.store = context.getStateStore(AccountTopology.STORE);
    }

    @Override
    public void process(Record<String, AccountCommand> record) {
        String account = record.key();
        AccountCommand cmd = record.value();
        AccountState state = store.get(account);
        if (state == null) {
            state = new AccountState(account, cmd.leg() == null ? null : cmd.leg().accountOpenedAt());
        }
        switch (cmd.kind()) {
            case LEG, REPORT -> {
                state.apply(cmd.leg(), pack);
                assess(account, state, cmd);
            }
            case HOLD -> state.setStatus(switch (cmd.holdStatus()) {
                case ON_HOLD -> AccountState.Status.ON_HOLD;
                case CONFIRMED_MULE -> AccountState.Status.CONFIRMED_MULE;
                case RELEASED -> AccountState.Status.CLEARED;
            });
        }
        store.put(account, state);
        features.save(state);
    }

    private void assess(String account, AccountState state, AccountCommand cmd) {
        MuleAssessment m = engine.assess(state, cmd.at(), 0);
        state.setMuleScore(m.score());
        AccountState.Status status = state.status();
        boolean open = status == AccountState.Status.NONE || status == AccountState.Status.FLAGGED;
        // A cleared account is only raised again on evidence strong enough for an automatic hold.
        boolean escalate = status == AccountState.Status.CLEARED && m.action() == MuleAssessment.Action.AUTO_HOLD;
        if (m.action() == MuleAssessment.Action.NONE || !(open || escalate)) {
            return;
        }
        state.setStatus(AccountState.Status.FLAGGED);
        List<AccountFlag.Reason> reasons = m.reasons().stream()
                .map(r -> new AccountFlag.Reason(r.code().name(), r.code().analystText(), r.weight())).toList();
        AccountFlag flag = new AccountFlag(account, m.score(),
                m.action() == MuleAssessment.Action.AUTO_HOLD ? AccountFlag.Action.AUTO_HOLD : AccountFlag.Action.FLAG,
                reasons, m.modelVersion(), pack.id(), cmd.at());
        context.forward(new Record<>(account, flag, cmd.at().toEpochMilli()));
    }
}
