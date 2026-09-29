package io.muleshield.core.risk;

/**
 * Why a payment was stopped or questioned. Each code has two texts: a precise one for the fraud
 * analyst, and a warning for the customer written for the scam it points at. A specific warning
 * shown at the moment of payment is what the UK PSR and Singapore's framework expect, and it is
 * what actually breaks a scammer's script; "are you sure?" does not.
 */
public enum ReasonCode {

    // ---- Hard policy stops
    KILL_SWITCH("The customer switched off digital payments",
            "You switched off payments from this account. Switch them back on in Security settings.", "SG-SRF / RBI-APP-DRAFT"),
    PAYEE_ON_HOLD("The receiving account is under a fraud hold",
            "We stopped this payment: the receiving account is under investigation for fraud.", "RBI-HOLD-SOP"),
    PROTECTIVE_DELAY("Push payment above the cooling-window amount to a payee not on the trusted list",
            "For your safety this payment will leave in one hour. You can cancel it until then.", "RBI-APP-DRAFT"),
    NEW_DEVICE_COOLING("High-risk payment during the cooling-off period after a new device was added",
            "You added a new device recently. For your safety, new payees and large payments unlock after 12 hours.", "SG-SRF"),
    DRAIN_PROTECTION("Most of the balance left the account within the drain window",
            "A large share of your balance is leaving quickly. We've paused this payment until you confirm it's you.", "SG-SRF"),

    // ---- Receiving account
    PAYEE_MULE_SUSPECT("The receiving account behaves like a money mule (fast pass-through, many unrelated senders)",
            "The account you're paying shows signs of being used by fraudsters.", "FATF mule indicators"),
    PAYEE_REPORTED("Other customers reported this account in scam complaints",
            "Other people have reported this account for scams.", "I4C / UK Finance"),
    PAYEE_INTEL("Another bank shared a fraud signal about this account",
            "Another bank has warned about this account.", "AU-SPF intelligence sharing"),
    PAYEE_FAN_IN("Many different people paid this account in the last 24 hours", null, "mule typology"),
    YOUNG_PAYEE_ACCOUNT("The receiving account was opened in the last 30 days", null, "mule typology"),
    NAME_MISMATCH("The payee name doesn't match the account holder",
            "The name you entered doesn't match the account. Check it with the person using a number you already know.",
            "EU-IPR VoP / UK CoP"),

    // ---- Payer behaviour and session
    NEW_PAYEE_HIGH_VALUE("A large first payment to a new payee",
            "This is a large first payment to someone new. If you were contacted out of the blue, stop.", "APP typology"),
    AMOUNT_UNUSUAL("Much larger than this customer's usual payments", null, "behavioural"),
    VELOCITY("Several payments in the last hour", null, "account takeover / coached victim"),
    MANY_NEW_PAYEES("Several new payees today", null, "account takeover / investment scam"),
    ACCOUNT_DRAIN("Most of the available balance is leaving",
            "This payment would empty most of your account.", "account takeover"),
    NEW_DEVICE("Paying from a device bound in the last 48 hours", null, "account takeover"),
    SIM_SWAP("The SIM changed in the last 72 hours",
            "Your SIM was changed recently. If you didn't do this, call us now on the number on your card.", "SIM swap"),
    REMOTE_ACCESS("Screen-sharing or remote-access software is running",
            "Close any screen-sharing app. Your bank will never ask you to install one.", "remote-access scam"),
    ON_CALL("The customer is on a phone call while paying",
            "Is someone on the phone telling you to make this payment? Police, courts and banks never ask you to move money to keep it safe.",
            "digital arrest / impersonation"),
    NIGHT("Paid between 11 pm and 6 am local time", null, "behavioural"),
    NEW_PAYER_ACCOUNT("The paying account was opened in the last 30 days", null, "mule typology"),

    // ---- Mule account behaviour (analyst-facing: the account holder is never tipped off)
    MULE_PASS_THROUGH("Money leaves soon after it arrives (most inbound value gone within an hour)", null, "FATF mule indicators"),
    MULE_FAN_IN("Many unrelated first-time senders in the last 24 hours", null, "FATF mule indicators"),
    MULE_FAN_OUT("Forwards to many beneficiaries", null, "layering"),
    MULE_CASH_OUT("Inbound money is withdrawn as cash or moved off-platform", null, "cash-out"),
    MULE_UPSTREAM("Receives money from accounts already flagged as mules", null, "mule chain (layer 2+)"),
    MULE_SHARED_DEVICE("Operated from a device that also runs other customers' accounts", null, "mule herder"),
    MULE_VOLUME("Inbound volume far above what the account normally sees", null, "FATF mule indicators"),
    MULE_DORMANT_REACTIVATED("A long-dormant account suddenly moving money", null, "rent-an-account"),
    MULE_YOUNG_ACCOUNT("Opened in the last 30 days and already moving money for others", null, "mule typology");

    private final String analystText;
    private final String customerText;
    private final String source;

    ReasonCode(String analystText, String customerText, String source) {
        this.analystText = analystText;
        this.customerText = customerText;
        this.source = source;
    }

    public String analystText() {
        return analystText;
    }

    /** The warning to show the customer, or null when this reason isn't worth a customer-facing message on its own. */
    public String customerText() {
        return customerText;
    }

    /** The rule or typology the reason comes from. */
    public String source() {
        return source;
    }
}
