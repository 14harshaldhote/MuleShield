package io.muleshield.sim;

import java.time.Duration;

/**
 * How people react to an intervention. These can't be read off public data, so they are stated
 * here, kept deliberately modest, and varied in the sensitivity run: the conclusions must hold at
 * the pessimistic end too.
 *
 * @param cancelAfterDelay      a scam victim cancels during a blanket delay that shows no specific warning
 *                              (the scammer is usually still on the phone telling them to wait)
 * @param cancelAfterWarning    ... during a hold that shows a warning written for the scam their signals point at
 * @param stopAfterBankCall     ... when the bank holds the payment for review and calls them
 * @param retryAfterDecline     a scammer has the victim try again (smaller, other payee) after a decline
 * @param passkeyStopsTakeover  a step-up signed with the customer's own passkey stops a takeover
 *                              (the fraudster holds the SIM, not the phone with the passkey)
 * @param passkeyStopsScam      ... stops an authorised scam (the victim signs; the signed amount and payee rarely change their mind)
 * @param analystConfirmsMule   an analyst reviewing a flagged mule confirms it
 * @param analystClearsLegit    ... clears a wrongly flagged legitimate account
 * @param reviewDelay           time from a flag to an analyst's decision
 * @param wrongHoldDuration     how long a wrongly held legitimate account stays held before the customer gets it released
 */
public record Assumptions(
        double cancelAfterDelay,
        double cancelAfterWarning,
        double stopAfterBankCall,
        double retryAfterDecline,
        double passkeyStopsTakeover,
        double passkeyStopsScam,
        double analystConfirmsMule,
        double analystClearsLegit,
        Duration reviewDelay,
        Duration wrongHoldDuration) {

    public static Assumptions central() {
        return new Assumptions(0.20, 0.45, 0.70, 0.50, 0.90, 0.05, 0.90, 0.97, Duration.ofHours(4), Duration.ofDays(3));
    }

    /** Victims listen half as often, scammers always retry, analysts are slower and less accurate. */
    public static Assumptions pessimistic() {
        return new Assumptions(0.10, 0.22, 0.45, 1.00, 0.75, 0.0, 0.80, 0.93, Duration.ofHours(12), Duration.ofDays(7));
    }
}
