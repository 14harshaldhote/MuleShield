package io.muleshield.core.payment;

/** What a payment is for. Policy packs use it: RBI's draft cooling window exempts merchant and recurring payments. */
public enum PaymentType {
    /** Person to person: the channel almost every authorised push payment scam uses. */
    P2P,
    /** Person to merchant. */
    P2M,
    BILL,
    RECURRING,
    SALARY,
    /** Money leaving the system (ATM withdrawal, card-less cash, crypto on-ramp): where mule chains end. */
    CASH_OUT
}
