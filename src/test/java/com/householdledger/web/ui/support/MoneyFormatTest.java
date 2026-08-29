package com.householdledger.web.ui.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place money becomes text, and therefore the one place a display bug
 * about money can hide (PRD §3.3).
 */
class MoneyFormatTest {

    @ParameterizedTest(name = "{0} paise reads as {1}")
    @CsvSource({
            "0,            0.00",
            "1,            0.01",
            "9,            0.09",
            "10,           0.10",
            "99,           0.99",
            "100,          1.00",
            "105,          1.05",
            "150,          1.50",
            "99999,        999.99",
            "-1,          -0.01",
            "-100,        -1.00",
            "-99999,      -999.99",
            "-150,        -1.50"
    })
    void formatsPaiseAsRupeesAndPaise(long minorUnits, String expected) {
        assertThat(MoneyFormat.format(minorUnits)).isEqualTo(expected);
    }

    /**
     * The last three digits, then groups of two — 12,34,567.89 rather than
     * 1,234,567.89. The PRD's amounts are rupees and paise, and a household
     * reading its own statements expects lakhs.
     */
    @Test
    @DisplayName("groups in the Indian convention: three digits, then twos")
    void groupsInTheIndianConvention() {
        assertThat(MoneyFormat.format(99_999L)).isEqualTo("999.99");
        assertThat(MoneyFormat.format(100_000L)).isEqualTo("1,000.00");
        assertThat(MoneyFormat.format(1_000_000L)).isEqualTo("10,000.00");
        assertThat(MoneyFormat.format(10_000_000L)).isEqualTo("1,00,000.00");
        assertThat(MoneyFormat.format(100_000_000L)).isEqualTo("10,00,000.00");
        assertThat(MoneyFormat.format(123_456_789L)).isEqualTo("12,34,567.89");
        assertThat(MoneyFormat.format(-123_456_789L)).isEqualTo("-12,34,567.89");
    }

    /**
     * Three rupee digits take no separator and four take one. That is the
     * boundary the grouping loop is most likely to get wrong, and the one
     * where an odd-length head first has to produce a single-digit group.
     */
    @Test
    void separatorAppearsExactlyAtFourRupeeDigits() {
        assertThat(MoneyFormat.format(99_999L)).doesNotContain(",");
        assertThat(MoneyFormat.format(100_000L)).contains(",");

        assertThat(MoneyFormat.format(999_99L)).isEqualTo("999.99");
        assertThat(MoneyFormat.format(1_000_00L)).isEqualTo("1,000.00");
        assertThat(MoneyFormat.format(10_000_00L)).isEqualTo("10,000.00");
    }

    /**
     * {@code Math.abs(Long.MIN_VALUE)} is itself, still negative. Formatting
     * works from the decimal digits instead, so the extremes format rather
     * than producing a wrong sign or throwing.
     */
    @Test
    void formatsTheExtremesWithoutOverflowing() {
        assertThat(MoneyFormat.format(Long.MIN_VALUE)).startsWith("-").endsWith(".08");
        assertThat(MoneyFormat.format(Long.MAX_VALUE)).doesNotStartWith("-");
        assertThat(MoneyFormat.format(Long.MAX_VALUE)).endsWith(".07");
        assertThat(MoneyFormat.formatMagnitude(Long.MIN_VALUE)).doesNotStartWith("-");
    }

    @Test
    void putsTheRupeeSignInsideTheMinus() {
        assertThat(MoneyFormat.withSymbol(420_000L)).isEqualTo("₹4,200.00");
        assertThat(MoneyFormat.withSymbol(-420_000L)).isEqualTo("-₹4,200.00");
    }

    @Test
    void magnitudeDiffersFromTheSignedFormOnlyByTheSign() {
        assertThat(MoneyFormat.formatMagnitude(-12_345L)).isEqualTo("123.45");
        assertThat(MoneyFormat.formatMagnitude(12_345L)).isEqualTo("123.45");
    }

    /**
     * Form redisplay uses the ungrouped form: a value that changes shape every
     * time a form is rejected looks like the field is mangling what was typed.
     */
    @Test
    void inputFormIsUngroupedSoRedisplayIsStable() {
        assertThat(MoneyFormat.forInput(123_456_789L)).isEqualTo("1234567.89");
        assertThat(MoneyFormat.forInput(-5L)).isEqualTo("-0.05");
        assertThat(MoneyFormat.forInput(0L)).isEqualTo("0.00");
        assertThat(MoneyFormat.forInput(100_000L)).doesNotContain(",");
    }
}
