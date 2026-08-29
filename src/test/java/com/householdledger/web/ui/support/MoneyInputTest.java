package com.householdledger.web.ui.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing what a member typed into integer minor units (PRD §3.3, §10).
 *
 * <p>The tests that matter most here are the rejections. A parser that
 * quietly rounds, truncates or reinterprets a malformed amount produces a
 * ledger entry that is wrong and looks fine.
 */
class MoneyInputTest {

    @ParameterizedTest(name = "\"{0}\" is {1} paise")
    @CsvSource({
            "'0',            0",
            "'0.00',         0",
            "'1',            100",
            "'1.5',          150",
            "'1.50',         150",
            "'1.05',         105",
            "'0.01',         1",
            "'.5',           50",
            "'.05',          5",
            "'12.',          1200",
            "'1234.50',      123450",
            "'1,234.50',     123450",
            "'1,23,456.78',  12345678",
            "'  42  ',       4200",
            "'+7.25',        725",
            "'-7.25',        -725"
    })
    void parsesWhatAMemberWouldType(String text, long expected) {
        assertThat(MoneyInput.parse(text)).isEqualTo(expected);
    }

    @Test
    void acceptsTheRupeeSignAndGroupingTheFormatterProduces() {
        assertThat(MoneyInput.parse("₹1,23,456.78")).isEqualTo(12_345_678L);
        assertThat(MoneyInput.parse(MoneyFormat.format(9_876_543L))).isEqualTo(9_876_543L);
    }

    /**
     * Three decimal places are refused rather than rounded. Turning 10.005
     * into 10.00 or 10.01 would be the application deciding what someone
     * meant about money.
     */
    @Test
    void refusesMoreThanTwoDecimalPlacesInsteadOfRounding() {
        assertThatThrownBy(() -> MoneyInput.parse("10.005"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two decimal places");

        assertThatThrownBy(() -> MoneyInput.parse("0.001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = {
            "abc", "1a", "a1", "1.2.3", "--5", "5-", "1e3", "1/2", "(5)", "1..5",
            "₹", "-", "+", ".", "1 000 . 5 0 x"
    })
    void rejectsAnythingThatIsNotAnAmount(String text) {
        assertThatThrownBy(() -> MoneyInput.parse(text))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void rejectsBlankInput(String text) {
        assertThatThrownBy(() -> MoneyInput.parse(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enter an amount");
    }

    /**
     * A ledger must not accept an amount it cannot store. The multiplication
     * by 100 is the overflow that a naive parser would wrap silently.
     */
    @Test
    void refusesAmountsTooLargeToStore() {
        assertThatThrownBy(() -> MoneyInput.parse("99999999999999999999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");

        assertThatThrownBy(() -> MoneyInput.parse(Long.toString(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    /**
     * Simple and split entry take a magnitude and derive the signs themselves
     * (PRD §FR-3). A negative there is not a small amount — it is a member
     * expressing direction in a field that does not carry it, and would
     * silently reverse the entry.
     */
    @Test
    void positiveOnlyParseRejectsZeroAndNegatives() {
        assertThat(MoneyInput.parsePositive("0.01")).isEqualTo(1L);

        assertThatThrownBy(() -> MoneyInput.parsePositive("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");

        assertThatThrownBy(() -> MoneyInput.parsePositive("-1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    /**
     * Every rejection message has to be safe to render: it describes the
     * input rule and names nothing internal (PRD §9).
     */
    @Test
    void failureMessagesDescribeTheRuleAndNothingElse() {
        for (String bad : new String[]{"abc", "1.234", "", "999999999999999999999"}) {
            assertThatThrownBy(() -> MoneyInput.parse(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .isNotBlank()
                            .doesNotContain("com.householdledger")
                            .doesNotContain("Exception")
                            .doesNotContain("null"));
        }
    }
}
