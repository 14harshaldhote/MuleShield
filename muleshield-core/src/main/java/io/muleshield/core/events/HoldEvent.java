package io.muleshield.core.events;

import java.time.Instant;

/**
 * A change to an account's debit hold, decided in case-service and enforced by payments-service
 * on the ledger.                                                                 [RBI-HOLD-SOP]
 *
 * @param until when the hold lapses unless the bank decides first (at most 60 days under RBI's draft SOP)
 */
public record HoldEvent(String account, Status status, String caseId, String reason, Instant at, Instant until) {

    public enum Status { ON_HOLD, CONFIRMED_MULE, RELEASED }
}
