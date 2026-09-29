package io.muleshield.core.events;

/**
 * Kafka topics between the services. Every topic is keyed by the account (or token) it is about,
 * so all events for one account land on one partition, in order.
 */
public final class Topics {

    /** Every payment's outcome, from payments-service's transactional outbox. Key: debtor account. */
    public static final String PAYMENTS = "payments.events";
    /** Accounts the mule detector flags. Key: account. From risk-service to case-service. */
    public static final String ACCOUNT_FLAGS = "risk.account-flags";
    /** Debit holds placed, confirmed or released. Key: account. From case-service to payments and risk. */
    public static final String HOLDS = "cases.holds";
    /** Scam reports from victims (a branch, the helpline, the national portal). Key: reported account. */
    public static final String REPORTS = "fraud.reports";
    /** Pseudonymous mule signals exchanged between banks. Key: token. */
    public static final String SHARED_SIGNALS = "intel.shared-signals";

    private Topics() {
    }
}
