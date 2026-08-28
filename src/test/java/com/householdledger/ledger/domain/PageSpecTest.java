package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paging bounds (PRD §FR-5). Out-of-range values are clamped rather than
 * rejected — an unbounded page size is what would actually threaten the
 * PRD §5 latency target, and clamping removes that possibility entirely.
 */
class PageSpecTest {

    @Test
    void defaultsToTheFirstPage() {
        assertThat(PageSpec.first().page()).isZero();
        assertThat(PageSpec.first().size()).isEqualTo(PageSpec.DEFAULT_SIZE);
    }

    @Test
    void aNegativePageIsClampedToZero() {
        assertThat(new PageSpec(-5, 10).page()).isZero();
    }

    @Test
    void aTooSmallSizeIsClampedUp() {
        assertThat(new PageSpec(0, 0).size()).isEqualTo(PageSpec.MIN_SIZE);
        assertThat(new PageSpec(0, -20).size()).isEqualTo(PageSpec.MIN_SIZE);
    }

    @Test
    void anExcessiveSizeIsClampedDown() {
        // The important one: no caller can request the whole ledger at once.
        assertThat(new PageSpec(0, 1_000_000).size()).isEqualTo(PageSpec.MAX_SIZE);
        assertThat(new PageSpec(0, Integer.MAX_VALUE).size()).isEqualTo(PageSpec.MAX_SIZE);
    }

    @Test
    void theMaximumItselfIsAllowed() {
        assertThat(new PageSpec(0, PageSpec.MAX_SIZE).size()).isEqualTo(PageSpec.MAX_SIZE);
    }

    @Test
    void ofAppliesDefaultsForNulls() {
        assertThat(PageSpec.of(null, null).page()).isZero();
        assertThat(PageSpec.of(null, null).size()).isEqualTo(PageSpec.DEFAULT_SIZE);
    }

    @Test
    void ofHonoursSuppliedValues() {
        assertThat(PageSpec.of(3, 50).page()).isEqualTo(3);
        assertThat(PageSpec.of(3, 50).size()).isEqualTo(50);
    }

    @Test
    void ofClampsSuppliedValuesToo() {
        assertThat(PageSpec.of(-1, 99_999).page()).isZero();
        assertThat(PageSpec.of(-1, 99_999).size()).isEqualTo(PageSpec.MAX_SIZE);
    }

    @Test
    void offsetIsPageTimesSize() {
        assertThat(new PageSpec(0, 25).offset()).isZero();
        assertThat(new PageSpec(3, 25).offset()).isEqualTo(75L);
    }

    @Test
    void offsetDoesNotOverflowForALargePageIndex() {
        // page * size as int would overflow; the long cast is the point.
        assertThat(new PageSpec(Integer.MAX_VALUE, PageSpec.MAX_SIZE).offset()).isPositive();
    }
}
