package io.muleshield.sim;

/**
 * What a simulated payment really is. The engine never sees this; the evaluation does.
 * The scam typologies are the ones driving 2025-26 losses in India's I4C data and UK Finance's
 * Annual Fraud Report.
 */
public enum Typology {
    LEGIT(false),
    /** A caller posing as police, CBI or customs keeps the victim on a video call and has them "park" savings in a "safe account". */
    DIGITAL_ARREST(true),
    /** A fake trading or crypto platform: small "deposits" that grow over weeks, often to a payee the victim was told to whitelist. */
    INVESTMENT(true),
    /** Fake customer support or a refund: the victim installs a screen-sharing app and is walked through the payment. */
    REMOTE_ACCESS(true),
    /** Account takeover after a SIM swap: the fraudster binds a new device and drains the account at night. Unauthorised. */
    SIM_SWAP_ATO(true),
    /** A mule forwarding received money to the next layer or cashing it out. Not a victim's payment. */
    MULE_LAYERING(false);

    private final boolean scam;

    Typology(boolean scam) {
        this.scam = scam;
    }

    /** A payment a victim loses money on (authorised push payment scam or account takeover). */
    public boolean isScam() {
        return scam;
    }

    public boolean isUnauthorised() {
        return this == SIM_SWAP_ATO;
    }
}
