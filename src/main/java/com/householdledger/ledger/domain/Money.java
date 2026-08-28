package com.householdledger.ledger.domain;

import java.util.Objects;

/**
 * Amounts are stored and manipulated exclusively in minor units (paise) as a
 * {@code long}. Never {@code float}, never {@code double}, never
 * {@code BigDecimal} anywhere near a monetary column or calculation — see
 * PRD §3.3. This type owns all arithmetic so that no other class ever
 * touches a raw {@code long} amount directly.
 *
 * <p>{@code Money} itself does not restrict sign: it is used both for
 * unsigned user-facing amounts (e.g. "amount strictly positive" on API
 * input, PRD §FR-3) and for signed posting deltas (PRD §3.1: "a signed
 * amount"). Callers enforce sign constraints where they matter — see
 * {@link Posting}.
 */
public record Money(long minorUnits) implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    public static Money ofMinor(long minorUnits) {
        return new Money(minorUnits);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(this.minorUnits, other.minorUnits));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(this.minorUnits, other.minorUnits));
    }

    public Money negate() {
        return new Money(Math.negateExact(this.minorUnits));
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public Money abs() {
        return minorUnits < 0L ? negate() : this;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.minorUnits, other.minorUnits);
    }

    /** Sums a collection of {@code Money}, returning {@link #ZERO} for an empty input. */
    public static Money sum(Iterable<Money> amounts) {
        Objects.requireNonNull(amounts, "amounts");
        Money total = ZERO;
        for (Money m : amounts) {
            total = total.plus(m);
        }
        return total;
    }

    @Override
    public String toString() {
        // Presentation-only helper; the domain never reasons about rupees vs
        // paise, only minor units. Sign is preserved (e.g. -42000 -> "-420.00").
        boolean negative = minorUnits < 0;
        long abs = Math.abs(minorUnits);
        long rupees = abs / 100;
        long paise = abs % 100;
        return (negative ? "-" : "") + rupees + "." + (paise < 10 ? "0" + paise : String.valueOf(paise));
    }
}
