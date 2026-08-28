package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The FR-5 filter criteria: normalisation, validation, and range semantics. */
class TransactionFilterTest {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    private static final LocalDate DEC = LocalDate.of(2026, 12, 31);

    private final UUID accountId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();

    @Test
    void retainsEveryCriterion() {
        TransactionFilter filter = new TransactionFilter(JAN, DEC, accountId, memberId, "groceries");

        assertThat(filter.from()).isEqualTo(JAN);
        assertThat(filter.to()).isEqualTo(DEC);
        assertThat(filter.accountId()).isEqualTo(accountId);
        assertThat(filter.memberId()).isEqualTo(memberId);
        assertThat(filter.descriptionContains()).isEqualTo("groceries");
    }

    @Test
    void unfilteredConstantConstrainsNothing() {
        assertThat(TransactionFilter.UNFILTERED.isUnfiltered()).isTrue();
        assertThat(TransactionFilter.UNFILTERED.searchTerm()).isEmpty();
        assertThat(TransactionFilter.UNFILTERED.account()).isEmpty();
        assertThat(TransactionFilter.UNFILTERED.member()).isEmpty();
        assertThat(TransactionFilter.UNFILTERED.fromDate()).isEmpty();
        assertThat(TransactionFilter.UNFILTERED.toDate()).isEmpty();
    }

    @Test
    void aPopulatedFilterIsNotUnfiltered() {
        assertThat(new TransactionFilter(JAN, null, null, null, null).isUnfiltered()).isFalse();
        assertThat(new TransactionFilter(null, null, accountId, null, null).isUnfiltered()).isFalse();
        assertThat(new TransactionFilter(null, null, null, memberId, null).isUnfiltered()).isFalse();
        assertThat(new TransactionFilter(null, null, null, null, "x").isUnfiltered()).isFalse();
    }

    @Test
    void aBlankSearchTermIsNormalisedAway() {
        // Left as-is, " " would become LIKE '%%' and match everything while
        // looking like an active filter.
        assertThat(new TransactionFilter(null, null, null, null, "   ").descriptionContains()).isNull();
        assertThat(new TransactionFilter(null, null, null, null, "").descriptionContains()).isNull();
        assertThat(new TransactionFilter(null, null, null, null, "  ").isUnfiltered()).isTrue();
    }

    @Test
    void aSearchTermIsTrimmed() {
        assertThat(new TransactionFilter(null, null, null, null, "  milk  ").descriptionContains())
                .isEqualTo("milk");
    }

    @Test
    void aReversedDateRangeIsRejected() {
        assertThatThrownBy(() -> new TransactionFilter(DEC, JAN, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reversed");
    }

    @Test
    void aSingleDayRangeIsAccepted() {
        assertThatCode(() -> new TransactionFilter(JAN, JAN, null, null, null)).doesNotThrowAnyException();
        assertThat(new TransactionFilter(JAN, JAN, null, null, null).includesDate(JAN)).isTrue();
    }

    @Test
    void theRangeIsInclusiveAtBothEnds() {
        TransactionFilter range = new TransactionFilter(JAN, DEC, null, null, null);

        assertThat(range.includesDate(JAN)).isTrue();
        assertThat(range.includesDate(DEC)).isTrue();
        assertThat(range.includesDate(JAN.minusDays(1))).isFalse();
        assertThat(range.includesDate(DEC.plusDays(1))).isFalse();
    }

    @Test
    void anOpenEndedRangeConstrainsOnlyTheSuppliedBound() {
        assertThat(new TransactionFilter(null, DEC, null, null, null).includesDate(LocalDate.of(1999, 1, 1)))
                .isTrue();
        assertThat(new TransactionFilter(JAN, null, null, null, null).includesDate(LocalDate.of(2999, 1, 1)))
                .isTrue();
    }
}
