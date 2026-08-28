package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PRD §FR-3: "Date not in the future beyond a small tolerance."
 *
 * <p>"Today" is supplied rather than read from a system clock, so the
 * boundary is asserted at its exact edge instead of approximately.
 */
class OccurredOnPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);

    @Test
    void backDatingIsUnrestricted() {
        // Households enter last week's receipts constantly; the ledger records
        // when money moved, not when someone typed it in.
        assertThatCode(() -> OccurredOnPolicy.requireNotTooFarInFuture(TODAY.minusYears(5), TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    void todayIsAccepted() {
        assertThatCode(() -> OccurredOnPolicy.requireNotTooFarInFuture(TODAY, TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    void oneDayAheadIsAcceptedAsTimezoneSlack() {
        // A member in Asia/Kolkata recording a late-evening expense is already
        // on tomorrow's date relative to a UTC server.
        assertThatCode(() -> OccurredOnPolicy.requireNotTooFarInFuture(TODAY.plusDays(1), TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    void twoDaysAheadIsRejected() {
        assertThatThrownBy(() -> OccurredOnPolicy.requireNotTooFarInFuture(TODAY.plusDays(2), TODAY))
                .isInstanceOf(FutureDatedTransactionException.class);
    }

    @Test
    void aFarFutureDateIsRejectedWithUsefulDetail() {
        assertThatThrownBy(() -> OccurredOnPolicy.requireNotTooFarInFuture(TODAY.plusYears(1), TODAY))
                .isInstanceOf(FutureDatedTransactionException.class)
                .hasMessageContaining("future")
                .hasMessageContaining("2027-08-28");
    }

    @Test
    void theExceptionCarriesTheOffendingAndLatestAllowedDates() {
        LocalDate offending = TODAY.plusDays(10);

        FutureDatedTransactionException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                FutureDatedTransactionException.class,
                () -> OccurredOnPolicy.requireNotTooFarInFuture(offending, TODAY));

        assertThat(thrown.occurredOn()).isEqualTo(offending);
        assertThat(thrown.latestAllowed()).isEqualTo(TODAY.plusDays(OccurredOnPolicy.FUTURE_TOLERANCE_DAYS));
    }

    @Test
    void isAcceptableMirrorsTheThrowingForm() {
        assertThat(OccurredOnPolicy.isAcceptable(TODAY.minusDays(1), TODAY)).isTrue();
        assertThat(OccurredOnPolicy.isAcceptable(TODAY, TODAY)).isTrue();
        assertThat(OccurredOnPolicy.isAcceptable(TODAY.plusDays(1), TODAY)).isTrue();
        assertThat(OccurredOnPolicy.isAcceptable(TODAY.plusDays(2), TODAY)).isFalse();
    }

    @Test
    void toleranceIsOneDay() {
        // Pinned deliberately: widening it silently would let speculative
        // future entries distort as-of balances.
        assertThat(OccurredOnPolicy.FUTURE_TOLERANCE_DAYS).isEqualTo(1);
    }

    @Test
    void nullsAreRejected() {
        assertThatThrownBy(() -> OccurredOnPolicy.requireNotTooFarInFuture(null, TODAY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OccurredOnPolicy.requireNotTooFarInFuture(TODAY, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theBoundaryHoldsAcrossAMonthEnd() {
        LocalDate monthEnd = LocalDate.of(2026, 1, 31);

        assertThat(OccurredOnPolicy.isAcceptable(LocalDate.of(2026, 2, 1), monthEnd)).isTrue();
        assertThat(OccurredOnPolicy.isAcceptable(LocalDate.of(2026, 2, 2), monthEnd)).isFalse();
    }
}
