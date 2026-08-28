package com.householdledger.ledger.domain;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for Phase 5's query value objects (PRD §7, §9 —
 * "property tests run at least 1000 generated cases and pass").
 *
 * <p>Two invariants worth expressing generatively rather than by example:
 *
 * <p><b>Paging is always within bounds.</b> Whatever integers arrive from a
 * client — negative, zero, {@code Integer.MAX_VALUE} — the resulting
 * {@code PageSpec} is a page index of at least zero and a size inside
 * {@code [MIN_SIZE, MAX_SIZE]}. This is the guard that makes an unbounded
 * result set impossible, so it should hold for every input rather than the
 * handful a unit test can enumerate.
 *
 * <p><b>A date range means exactly what it says.</b> For any valid range, both
 * endpoints are inside it and the days immediately outside are not —
 * inclusive at both ends, with no off-by-one at either boundary.
 */
class QueryPropertyTest {

    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    @Property(tries = 1000)
    void anyPageSpecIsClampedIntoBounds(
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = Integer.MAX_VALUE) int page,
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = Integer.MAX_VALUE) int size) {

        PageSpec spec = new PageSpec(page, size);

        assertThat(spec.page()).isNotNegative();
        assertThat(spec.size()).isBetween(PageSpec.MIN_SIZE, PageSpec.MAX_SIZE);
        assertThat(spec.offset()).isNotNegative();
    }

    @Property(tries = 1000)
    void anyClientSuppliedPagingIsAlsoClamped(
            @ForAll @IntRange(min = -10_000, max = 10_000) int page,
            @ForAll @IntRange(min = -10_000, max = 10_000) int size) {

        PageSpec spec = PageSpec.of(page, size);

        assertThat(spec.page()).isNotNegative();
        assertThat(spec.size()).isBetween(PageSpec.MIN_SIZE, PageSpec.MAX_SIZE);
    }

    @Property(tries = 1000)
    void anyValidRangeIsInclusiveAtBothEndsAndExcludesJustOutside(
            @ForAll @LongRange(min = 0, max = 20_000) long firstOffset,
            @ForAll @LongRange(min = 0, max = 20_000) long secondOffset) {

        LocalDate low = EPOCH.plusDays(Math.min(firstOffset, secondOffset));
        LocalDate high = EPOCH.plusDays(Math.max(firstOffset, secondOffset));

        TransactionFilter filter = new TransactionFilter(low, high, null, null, null);

        assertThat(filter.includesDate(low)).isTrue();
        assertThat(filter.includesDate(high)).isTrue();
        assertThat(filter.includesDate(low.minusDays(1))).isFalse();
        assertThat(filter.includesDate(high.plusDays(1))).isFalse();
    }

    @Property(tries = 1000)
    void anyReversedRangeIsRejected(
            @ForAll @LongRange(min = 0, max = 10_000) long baseOffset,
            @ForAll @LongRange(min = 1, max = 5_000) long gap) {

        LocalDate low = EPOCH.plusDays(baseOffset);
        LocalDate high = low.plusDays(gap);

        assertThatThrownBy(() -> new TransactionFilter(high, low, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 1000)
    void anyWhitespaceOnlySearchTermNormalisesToUnfiltered(
            @ForAll @IntRange(min = 1, max = 40) int spaceCount) {

        String blank = " ".repeat(spaceCount);

        TransactionFilter filter = new TransactionFilter(null, null, null, null, blank);

        assertThat(filter.descriptionContains()).isNull();
        assertThat(filter.isUnfiltered()).isTrue();
    }
}
