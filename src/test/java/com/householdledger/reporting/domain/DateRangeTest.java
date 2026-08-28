package com.householdledger.reporting.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Inclusive-at-both-ends reporting range (PRD §FR-5). */
class DateRangeTest {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_31 = LocalDate.of(2026, 3, 31);

    @Test
    void bothEndpointsAreInsideTheRange() {
        // The 31st matters: a month report that silently drops its last day
        // understates the month and nothing about the number looks wrong.
        DateRange march = new DateRange(MARCH_1, MARCH_31);

        assertThat(march.includes(MARCH_1)).isTrue();
        assertThat(march.includes(MARCH_31)).isTrue();
    }

    @Test
    void daysJustOutsideAreExcluded() {
        DateRange march = new DateRange(MARCH_1, MARCH_31);

        assertThat(march.includes(MARCH_1.minusDays(1))).isFalse();
        assertThat(march.includes(MARCH_31.plusDays(1))).isFalse();
    }

    @Test
    void lengthCountsBothEndpoints() {
        assertThat(new DateRange(MARCH_1, MARCH_31).lengthInDays()).isEqualTo(31);
    }

    @Test
    void aSingleDayIsAValidRangeOfLengthOne() {
        DateRange oneDay = DateRange.singleDay(MARCH_1);

        assertThat(oneDay.lengthInDays()).isEqualTo(1);
        assertThat(oneDay.includes(MARCH_1)).isTrue();
        assertThat(oneDay.includes(MARCH_1.plusDays(1))).isFalse();
    }

    @Test
    void equalBoundsAreAccepted() {
        assertThatCode(() -> new DateRange(MARCH_1, MARCH_1)).doesNotThrowAnyException();
    }

    @Test
    void aReversedRangeIsRejected() {
        assertThatThrownBy(() -> new DateRange(MARCH_31, MARCH_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reversed");
    }

    @Test
    void nullBoundsAreRejected() {
        assertThatThrownBy(() -> new DateRange(null, MARCH_31)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DateRange(MARCH_1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRangeSpanningAYearBoundaryBehavesNormally() {
        DateRange range = new DateRange(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1));

        assertThat(range.lengthInDays()).isEqualTo(2);
        assertThat(range.includes(LocalDate.of(2025, 12, 31))).isTrue();
        assertThat(range.includes(LocalDate.of(2026, 1, 1))).isTrue();
    }
}
