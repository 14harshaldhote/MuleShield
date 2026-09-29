package io.muleshield.core.mule;

import java.util.List;

import io.muleshield.core.risk.Reason;

/**
 * The mule detector's view of one account.
 *
 * @param action NONE, FLAG (open a case for an analyst) or AUTO_HOLD (place a temporary debit hold
 *               now, then maker-checker review within the SOP's deadlines)
 */
public record MuleAssessment(String account, double score, Action action, List<Reason> reasons, String modelVersion,
                             double[] features) {

    public enum Action { NONE, FLAG, AUTO_HOLD }

    public MuleAssessment {
        reasons = List.copyOf(reasons);
    }
}
