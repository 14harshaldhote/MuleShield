package io.muleshield.core.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.muleshield.core.ml.Scorer;
import io.muleshield.core.money.Money;
import io.muleshield.core.payment.PaymentInstruction;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.time.BusinessCalendar;

/**
 * Decides one payment in three layers:
 * <ol>
 *   <li><b>Hard stops</b> that no score overrides: the payer's kill switch, a payee under a fraud hold.</li>
 *   <li><b>The model</b>: a probability of scam or takeover, with each feature's contribution.</li>
 *   <li><b>The jurisdiction's rules</b>, from the policy pack: what a high score is allowed to do
 *       (the UK may delay four business days, EU instant payments can't be delayed so they're
 *       declined), plus blanket rules such as India's draft one-hour delay or Singapore's
 *       new-device cooling-off.</li>
 * </ol>
 * The same engine and model run in every country; only the pack changes.
 * <p>
 * Pure and thread-safe: no I/O, so it is the same code in the service and in the simulator that
 * measures it.
 */
public final class RiskEngine {

    /** Contributions smaller than this (log-odds) don't become reasons: they didn't change the outcome. */
    static final double MIN_REASON_WEIGHT = 0.15;
    static final int MAX_REASONS = 4;

    private final PolicyPack pack;
    private final Scorer scorer;
    private final BusinessCalendar calendar;

    public RiskEngine(PolicyPack pack, Scorer scorer) {
        this.pack = pack;
        this.scorer = scorer;
        this.calendar = BusinessCalendar.of(pack);
    }

    public PolicyPack pack() {
        return pack;
    }

    public Scorer scorer() {
        return scorer;
    }

    public RiskDecision assess(PaymentContext ctx) {
        PaymentInstruction p = ctx.payment();
        if (!p.amount().currency().equals(pack.currency())) {
            throw new IllegalArgumentException("Pack " + pack.id() + " is for " + pack.currency() + ", payment is in " + p.amount().currency());
        }
        double[] x = PaymentFeatures.extract(ctx, pack);
        Scorer.Score s = scorer.score(x);
        double score = s.probability();
        List<Reason> modelReasons = explain(x, s);
        Instant now = ctx.now();
        var b = new Builder(p, score, x);

        // 1. Hard stops.
        if (ctx.payer().killSwitchOn()) {
            return b.decline(Reason.policy(ReasonCode.KILL_SWITCH, "CUSTOMER-SETTING"));
        }
        if (ctx.payee().onDebitHold()) {
            return b.decline(Reason.policy(ReasonCode.PAYEE_ON_HOLD, pack.debitHold().ref()));
        }

        // 2. Score bands.
        var t = pack.thresholds();
        if (score >= t.block()) {
            return b.reasons(modelReasons).decline();
        }
        var cooling = pack.newDeviceCooling();
        if (cooling.enabled() && hoursSince(ctx.session().deviceBoundAt(), now) < cooling.duration().toMinutes() / 60.0
                && (!ctx.payer().knowsPayee() || score >= t.stepUp())) {
            return b.reason(Reason.policy(ReasonCode.NEW_DEVICE_COOLING, cooling.ref())).reasons(modelReasons).decline();
        }
        if (score >= t.hold()) {
            var hold = pack.riskHold();
            if (!hold.enabled()) {
                // No delay is allowed (EU instant payments must settle in seconds), so a payment too risky to release is refused.
                return b.reasons(modelReasons).decline();
            }
            Instant until = hold.businessDays() > 0
                    ? calendar.endOfNthBusinessDayAfter(now, hold.businessDays())
                    : now.plus(hold.duration());
            b.hold(until, true).reasons(modelReasons);
        } else if (score >= t.stepUp()) {
            b.reasons(modelReasons);
        }

        // 3. Blanket rules.
        var drain = pack.drainProtection();
        if (drain.enabled() && drainShare(ctx) >= drain.balanceShare()) {
            b.hold(now.plus(pack.riskHold().duration()), true).reason(Reason.policy(ReasonCode.DRAIN_PROTECTION, drain.ref()));
        }
        var delay = pack.protectiveDelay();
        if (delay.enabled() && delay.paymentTypes().contains(p.type())
                && p.amount().isAtLeast(delay.minAmount(pack.currency()))
                && !(delay.trustedPayeeBypass() && ctx.payer().payeeTrusted())) {
            b.hold(now.plus(delay.duration()), false).reason(Reason.policy(ReasonCode.PROTECTIVE_DELAY, delay.ref()));
        }
        if (score >= t.stepUp()) {
            b.stepUp();
        }
        return b.build();
    }

    /** Positive, reportable contributions, merged by reason and ranked. */
    List<Reason> explain(double[] x, Scorer.Score s) {
        Map<ReasonCode, Double> byCode = new EnumMap<>(ReasonCode.class);
        PaymentFeature[] features = PaymentFeature.values();
        for (int i = 0; i < features.length; i++) {
            double c = s.contributions()[i];
            if (c > 0 && features[i].reportable(x[i])) {
                byCode.merge(features[i].reason(), c, Double::sum);
            }
        }
        return byCode.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_REASON_WEIGHT)
                .sorted(Map.Entry.<ReasonCode, Double>comparingByValue().reversed())
                .limit(MAX_REASONS)
                .map(e -> new Reason(e.getKey(), e.getValue(), e.getKey().source()))
                .toList();
    }

    /** Share of the balance at the start of the last hour that is leaving within it, this payment included. */
    static double drainShare(PaymentContext ctx) {
        double out1h = ctx.payer().txAmount1h().major();
        double start = ctx.payer().availableBalance().major() + out1h;
        return start <= 0 ? 1 : (out1h + ctx.payment().amount().major()) / start;
    }

    private static double hoursSince(Instant then, Instant now) {
        return then == null ? Double.MAX_VALUE : Duration.between(then, now).toMinutes() / 60.0;
    }

    private final class Builder {
        private final PaymentInstruction p;
        private final double score;
        private final double[] x;
        private final List<Reason> reasons = new ArrayList<>();
        private Action action = Action.ALLOW;
        private Instant holdUntil;
        private boolean review;
        private boolean stepUp;

        Builder(PaymentInstruction p, double score, double[] x) {
            this.p = p;
            this.score = score;
            this.x = x;
        }

        Builder reason(Reason r) {
            if (reasons.stream().noneMatch(e -> e.code() == r.code())) {
                reasons.add(r);
            }
            return this;
        }

        Builder reasons(List<Reason> rs) {
            rs.forEach(this::reason);
            return this;
        }

        Builder hold(Instant until, boolean needsReview) {
            action = Action.HOLD;
            holdUntil = holdUntil == null || until.isAfter(holdUntil) ? until : holdUntil;
            review |= needsReview;
            return this;
        }

        Builder stepUp() {
            stepUp = true;
            return this;
        }

        RiskDecision decline(Reason r) {
            return reason(r).decline();
        }

        RiskDecision decline() {
            action = Action.DECLINE;
            holdUntil = null;
            stepUp = false;
            review = false;
            return build();
        }

        RiskDecision build() {
            List<Reason> ordered = reasons.stream()
                    .sorted(Comparator.comparing(Reason::isPolicy).reversed()
                            .thenComparing(Comparator.comparingDouble(Reason::weight).reversed()))
                    .toList();
            return new RiskDecision(p.uetr(), action, stepUp, holdUntil, action == Action.HOLD, review, score, ordered,
                    action == Action.ALLOW && !stepUp ? null : message(ordered), exposure(p.amount()),
                    scorer.version(), pack.id(), x);
        }

        /** The rule that applied ("this payment leaves in one hour"), then the warning for the scam the signals point at. */
        private static String message(List<Reason> ordered) {
            String notice = firstText(ordered, true);
            String warning = firstText(ordered, false);
            if (notice == null) {
                return warning;
            }
            return warning == null ? notice : notice + " " + warning;
        }

        private static String firstText(List<Reason> ordered, boolean policy) {
            return ordered.stream().filter(r -> r.isPolicy() == policy).map(r -> r.code().customerText())
                    .filter(m -> m != null).findFirst().orElse(null);
        }
    }

    /** Under mandatory reimbursement (UK), what the sending bank would owe for this payment if it turns out to be a scam. */
    Money exposure(Money amount) {
        var r = pack.reimbursement();
        if (!r.mandatory()) {
            return Money.zero(amount.currency());
        }
        Money cap = Money.of(r.maxAmount(), amount.currency());
        Money covered = amount.isAtLeast(cap) ? cap : amount;
        return new Money(Math.round(covered.minor() * r.sendingBankShare()), amount.currency());
    }
}
