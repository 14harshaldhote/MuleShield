package io.muleshield.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount in ISO 4217 minor units (paise, pence, cents). Money is never a floating-point value
 * here; {@link #major()} exists only to feed model features.                         [ISO 4217]
 */
public record Money(long minor, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        Currency.getInstance(currency);   // rejects codes that aren't ISO 4217
    }

    public static Money of(String major, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        long minor = new BigDecimal(major).setScale(digits, RoundingMode.UNNECESSARY).movePointRight(digits).longValueExact();
        return new Money(minor, currency);
    }

    public static Money of(BigDecimal major, String currency) {
        return of(major.toPlainString(), currency);
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(minor, Currency.getInstance(currency).getDefaultFractionDigits());
    }

    public double major() {
        return toBigDecimal().doubleValue();
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public boolean isAtLeast(Money other) {
        return compareTo(other) >= 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return currency + " " + toBigDecimal().toPlainString();
    }
}
