package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link Money} behaviour beyond the core arithmetic asserted in
 * {@code MoneyTest}: magnitude, ordering, overflow guards, and the
 * minor-unit-to-display conversion in {@code toString}.
 *
 * <p>{@code toString} carries real logic worth pinning down — sign handling
 * and zero-padding of the paise component — and PRD §3.3 is emphatic that
 * money is only ever a {@code long} of minor units, so the one place that
 * renders it as rupees is exactly where an off-by-a-factor-of-ten bug would
 * hide.
 */
class MoneyValueSemanticsTest {

    @Test
    void absReturnsMagnitudeForNegativeAmounts() {
        assertThat(Money.ofMinor(-420_000).abs()).isEqualTo(Money.ofMinor(420_000));
    }

    @Test
    void absLeavesPositiveAmountsUnchanged() {
        Money positive = Money.ofMinor(420_000);
        assertThat(positive.abs()).isEqualTo(positive);
    }

    @Test
    void absOfZeroIsZero() {
        assertThat(Money.ZERO.abs()).isEqualTo(Money.ZERO);
    }

    @Test
    void minorUnitsAccessorReturnsRawLong() {
        assertThat(Money.ofMinor(-1234).minorUnits()).isEqualTo(-1234L);
    }

    @Test
    void toStringRendersWholeRupeesWithTwoDecimalPlaces() {
        assertThat(Money.ofMinor(420_000)).hasToString("4200.00");
    }

    @Test
    void toStringPadsSinglePaiseDigit() {
        // 1205 paise is 12.05, not 12.5 — the padding branch.
        assertThat(Money.ofMinor(1205)).hasToString("12.05");
    }

    @Test
    void toStringRendersTwoPaiseDigitsWithoutPadding() {
        assertThat(Money.ofMinor(1250)).hasToString("12.50");
    }

    @Test
    void toStringRendersNegativeAmountsWithLeadingSign() {
        assertThat(Money.ofMinor(-1205)).hasToString("-12.05");
    }

    @Test
    void toStringRendersZero() {
        assertThat(Money.ZERO).hasToString("0.00");
    }

    @Test
    void toStringRendersSubRupeeAmounts() {
        assertThat(Money.ofMinor(7)).hasToString("0.07");
    }

    @Test
    void compareToTreatsEqualAmountsAsEqual() {
        assertThat(Money.ofMinor(500).compareTo(Money.ofMinor(500))).isZero();
    }

    @Test
    void compareToOrdersNegativeBelowPositive() {
        assertThat(Money.ofMinor(-1).compareTo(Money.ofMinor(1))).isNegative();
    }

    @Test
    void subtractionOverflowThrowsRatherThanWrapping() {
        assertThatThrownBy(() -> Money.ofMinor(Long.MIN_VALUE).minus(Money.ofMinor(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negationOverflowThrowsRatherThanWrapping() {
        assertThatThrownBy(() -> Money.ofMinor(Long.MIN_VALUE).negate())
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void sumRejectsNullInput() {
        assertThatThrownBy(() -> Money.sum(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amounts");
    }

    @Test
    void equalityAndHashCodeFollowRecordSemantics() {
        assertThat(Money.ofMinor(100)).isEqualTo(Money.ofMinor(100));
        assertThat(Money.ofMinor(100)).hasSameHashCodeAs(Money.ofMinor(100));
        assertThat(Money.ofMinor(100)).isNotEqualTo(Money.ofMinor(101));
    }
}
