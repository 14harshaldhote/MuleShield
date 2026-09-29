package io.muleshield.core.events;

import java.time.Instant;
import java.util.List;

/** The mule detector's finding about one account, for case-service to open (or escalate) a case. */
public record AccountFlag(
        String account,
        double score,
        Action action,
        List<Reason> reasons,
        String modelVersion,
        String packId,
        Instant at) {

    public enum Action { FLAG, AUTO_HOLD }

    public record Reason(String code, String text, double weight) {
    }
}
