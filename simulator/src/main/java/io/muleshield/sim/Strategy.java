package io.muleshield.sim;

/** The defences compared in the evaluation, from doing nothing to the full system shared across banks. */
public enum Strategy {
    /** No intervention: every payment goes through. Also the "shadow mode" used to log training data. */
    NONE("No controls", false, false, false),
    /** A typical legacy rule: hold new-payee payments above twice the monthly-pay scale for review. */
    LEGACY_RULES("Legacy amount rule", false, false, false),
    /** Only the blanket rules of the policy pack (India: the draft 1-hour delay on ₹10,000+ push payments). */
    POLICY_ONLY("Blanket policy only (e.g. RBI 1-hour delay)", false, false, false),
    /** MuleShield with hand-written scoring rules for payments and mules. */
    MULESHIELD_RULES("MuleShield, rule scorers", true, false, false),
    /** MuleShield with the trained models. */
    MULESHIELD_ML("MuleShield, trained models", true, true, false),
    /**
     * What-if: the trained models with the blanket delay switched off, so only payments the model finds
     * risky are held. Tests whether risk-based holds could replace a delay on every large payment.
     */
    RISK_BASED_ONLY("MuleShield, models, no blanket delay", true, true, false),
    /** MuleShield with the trained models, deployed at every bank, sharing pseudonymous mule signals. */
    MULESHIELD_CONSORTIUM("MuleShield, models + cross-bank sharing", true, true, true);

    private final String label;
    private final boolean engine;
    private final boolean models;
    private final boolean consortium;

    Strategy(String label, boolean engine, boolean models, boolean consortium) {
        this.label = label;
        this.engine = engine;
        this.models = models;
        this.consortium = consortium;
    }

    public String label() {
        return label;
    }

    /** Runs the risk engine on payments and the mule detector on accounts. */
    public boolean usesEngine() {
        return engine;
    }

    public boolean usesModels() {
        return models;
    }

    public boolean consortium() {
        return consortium;
    }
}
