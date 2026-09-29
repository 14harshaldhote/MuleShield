package io.muleshield.core.risk;

/**
 * One reason behind a decision.
 *
 * @param weight how much it pushed the score, in log-odds; policy rules that apply regardless of
 *               the score have {@link Double#POSITIVE_INFINITY}
 * @param rule   the policy-pack reference the reason enforces, or the typology it comes from
 */
public record Reason(ReasonCode code, double weight, String rule) {

    public static Reason policy(ReasonCode code, String ref) {
        return new Reason(code, Double.POSITIVE_INFINITY, ref);
    }

    public boolean isPolicy() {
        return Double.isInfinite(weight);
    }
}
