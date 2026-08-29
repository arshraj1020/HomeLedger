package com.householdledger.web.ui.support;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of the text↔minor-units conversion, checked over generated
 * amounts rather than chosen ones.
 *
 * <p>The round trip is the property that matters. A formatter and a parser
 * that disagree anywhere would let a form redisplay a rejected entry with a
 * different amount than the member typed — a corruption that no single
 * example test is likely to land on.
 */
class MoneyTextPropertyTest {

    /** Bounded well inside the range where x100 cannot overflow on the way back. */
    private static final long BOUND = 90_000_000_000_000_00L;

    @Property(tries = 2000)
    void formattedAmountsParseBackToThemselves(@ForAll @LongRange(min = -BOUND, max = BOUND) long minorUnits) {
        assertThat(MoneyInput.parse(MoneyFormat.format(minorUnits))).isEqualTo(minorUnits);
    }

    @Property(tries = 2000)
    void theInputFormAlsoParsesBackToItself(@ForAll @LongRange(min = -BOUND, max = BOUND) long minorUnits) {
        assertThat(MoneyInput.parse(MoneyFormat.forInput(minorUnits))).isEqualTo(minorUnits);
    }

    @Property(tries = 1000)
    void formattingNeverLosesTheSign(@ForAll @LongRange(min = -BOUND, max = BOUND) long minorUnits) {
        String text = MoneyFormat.format(minorUnits);
        assertThat(text.startsWith("-")).isEqualTo(minorUnits < 0L);
    }

    @Property(tries = 1000)
    void everyFormattedAmountHasExactlyTwoDecimalPlaces(
            @ForAll @LongRange(min = -BOUND, max = BOUND) long minorUnits) {

        String text = MoneyFormat.format(minorUnits);
        int point = text.indexOf('.');

        assertThat(point).isPositive();
        assertThat(text.length() - point - 1).isEqualTo(2);
        assertThat(text.indexOf('.', point + 1)).isEqualTo(-1);
    }

    @Property(tries = 1000)
    void magnitudeIsTheFormattedAmountWithoutItsSign(
            @ForAll @LongRange(min = -BOUND, max = BOUND) long minorUnits) {

        String signed = MoneyFormat.format(minorUnits);
        String magnitude = MoneyFormat.formatMagnitude(minorUnits);

        assertThat(magnitude).isEqualTo(signed.startsWith("-") ? signed.substring(1) : signed);
    }
}
