package io.muleshield.core.mule;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.muleshield.core.ml.Scorer;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.risk.Reason;
import io.muleshield.core.risk.ReasonCode;
import io.muleshield.core.state.AccountState;

/**
 * Scores an account for mule behaviour after each movement of money and says whether to open a
 * case or to hold debits straight away. Holding the account while the money is still in it is
 * the only point at which a scam victim's money can still be recovered: once it has passed through
 * three layers of mules and been withdrawn, it is gone.                           [RBI-HOLD-SOP]
 */
public final class MuleEngine {

    static final double MIN_REASON_WEIGHT = 0.15;

    private final PolicyPack pack;
    private final Scorer scorer;

    public MuleEngine(PolicyPack pack, Scorer scorer) {
        this.pack = pack;
        this.scorer = scorer;
    }

    public Scorer scorer() {
        return scorer;
    }

    public MuleAssessment assess(AccountState state, Instant now, double intelScore) {
        double[] x = MuleFeatures.extract(state, now, pack, intelScore);
        Scorer.Score s = scorer.score(x);
        var t = pack.thresholds();
        MuleAssessment.Action action = s.probability() >= t.muleAutoHold() && pack.debitHold().enabled()
                ? MuleAssessment.Action.AUTO_HOLD
                : s.probability() >= t.muleFlag() ? MuleAssessment.Action.FLAG : MuleAssessment.Action.NONE;
        return new MuleAssessment(state.account(), s.probability(), action, explain(x, s), scorer.version(), x);
    }

    static List<Reason> explain(double[] x, Scorer.Score s) {
        Map<ReasonCode, Double> byCode = new EnumMap<>(ReasonCode.class);
        MuleFeature[] features = MuleFeature.values();
        for (int i = 0; i < features.length; i++) {
            double c = s.contributions()[i];
            if (c > 0 && features[i].reportable(x[i])) {
                byCode.merge(features[i].reason(), c, Double::sum);
            }
        }
        return byCode.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_REASON_WEIGHT)
                .sorted(Map.Entry.<ReasonCode, Double>comparingByValue().reversed())
                .limit(4)
                .map(e -> new Reason(e.getKey(), e.getValue(), e.getKey().source()))
                .toList();
    }
}
