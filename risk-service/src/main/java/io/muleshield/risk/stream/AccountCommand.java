package io.muleshield.risk.stream;

import java.time.Instant;

import io.muleshield.core.events.HoldEvent;
import io.muleshield.core.state.AccountEvent;

/** Anything that changes an account's state, routed to the partition that owns the account. */
record AccountCommand(Kind kind, AccountEvent leg, HoldEvent.Status holdStatus, Instant at) {

    enum Kind { LEG, REPORT, HOLD }

    static AccountCommand leg(AccountEvent e) {
        return new AccountCommand(Kind.LEG, e, null, e.at());
    }

    static AccountCommand report(String account, Instant at) {
        return new AccountCommand(Kind.REPORT, AccountEvent.scamReport(account, at), null, at);
    }

    static AccountCommand hold(HoldEvent e) {
        return new AccountCommand(Kind.HOLD, null, e.status(), e.at());
    }
}
