package io.muleshield.core.events;

import java.time.Instant;
import java.util.UUID;

/** A victim's report naming the account they paid. */
public record ScamReport(String reportId, String victimAccount, String reportedAccount, long amountMinor, String currency,
                         UUID uetr, String channel, Instant reportedAt) {
}
