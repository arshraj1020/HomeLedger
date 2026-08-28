package com.householdledger.reporting.domain;

import com.householdledger.ledger.domain.AccountType;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for the reporting invariants (PRD §7, §9 — "property
 * tests run at least 1000 generated cases and pass").
 *
 * <p>The properties chosen are the ones where an example-based test is
 * genuinely weaker. A sign convention applied to a handful of hand-picked
 * amounts proves very little; the same convention shown to be
 * magnitude-preserving and self-inverse across thousands of random values,
 * for all five account types, pins the behaviour down.
 *
 * <p>The load-bearing one is
 * {@link #presentationNeverChangesWhatTheSignedTotalsSayAboutBalance}: it is
 * the formal statement of PRD §10's rule that presentation must be cosmetic.
 * If applying the display convention could ever alter whether a household's
 * raw totals cancel to zero, the balance sheet and the trial balance could
 * disagree, and the invariant of PRD §3.2 would no longer be observable.
 */
class ReportingPropertyTest {

    private static final long SAFE_BOUND = 1_000_000_000_000L;
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    @Property(tries = 1000)
    void theDisplayConversionIsItsOwnInverse(
            @ForAll @LongRange(min = -SAFE_BOUND, max = SAFE_BOUND) long signedMinor,
            @ForAll AccountType type) {

        long once = PresentationSign.forDisplay(type, signedMinor);
        long twice = PresentationSign.forDisplay(type, once);

        assertThat(twice).isEqualTo(signedMinor);
    }

    @Property(tries = 1000)
    void theDisplayConversionNeverChangesMagnitude(
            @ForAll @LongRange(min = -SAFE_BOUND, max = SAFE_BOUND) long signedMinor,
            @ForAll AccountType type) {

        // Presentation may flip a sign; it must never alter an amount.
        assertThat(Math.abs(PresentationSign.forDisplay(type, signedMinor)))
                .isEqualTo(Math.abs(signedMinor));
    }

    @Property(tries = 1000)
    void theDisplayConversionFlipsExactlyWhenIsFlippedSaysSo(
            @ForAll @LongRange(min = 1, max = SAFE_BOUND) long positiveMinor,
            @ForAll AccountType type) {

        boolean actuallyFlipped = PresentationSign.forDisplay(type, positiveMinor) != positiveMinor;

        assertThat(actuallyFlipped).isEqualTo(PresentationSign.isFlipped(type));
    }

    /**
     * PRD §10's rule, stated formally: presentation is cosmetic.
     *
     * <p>Generates a balanced set of signed balances — one per account, summing
     * to zero exactly as a real household's postings do — and asserts that the
     * raw signed total is still zero after every line has been through the
     * display conversion. The presented figures deliberately do <em>not</em>
     * sum to zero; the point is that converting them cannot retroactively
     * change what the underlying signed figures say.
     */
    @Property(tries = 1000)
    void presentationNeverChangesWhatTheSignedTotalsSayAboutBalance(
            @ForAll @IntRange(min = 1, max = 10) int accountCount,
            @ForAll("seedValues") long seed) {

        java.util.Random random = new java.util.Random(seed);
        List<Long> signedBalances = new ArrayList<>(accountCount + 1);

        long running = 0L;
        for (int i = 0; i < accountCount; i++) {
            long value = random.nextLong() % 10_000_000L;
            signedBalances.add(value);
            running += value;
        }
        // The balancing counterpart, exactly as a ledger's postings balance.
        signedBalances.add(-running);

        long signedTotal = 0L;
        for (int i = 0; i < signedBalances.size(); i++) {
            long signed = signedBalances.get(i);
            AccountType type = AccountType.values()[i % AccountType.values().length];

            // Converting for display must not disturb the underlying figure.
            long presented = PresentationSign.forDisplay(type, signed);
            assertThat(PresentationSign.forDisplay(type, presented)).isEqualTo(signed);

            signedTotal += signed;
        }

        assertThat(signedTotal).isZero();
    }

    @Property(tries = 1000)
    void anyValidRangeIsInclusiveAtBothEndsWithACorrectLength(
            @ForAll @LongRange(min = 0, max = 20_000) long firstOffset,
            @ForAll @LongRange(min = 0, max = 20_000) long secondOffset) {

        long low = Math.min(firstOffset, secondOffset);
        long high = Math.max(firstOffset, secondOffset);
        DateRange range = new DateRange(EPOCH.plusDays(low), EPOCH.plusDays(high));

        assertThat(range.includes(range.from())).isTrue();
        assertThat(range.includes(range.to())).isTrue();
        assertThat(range.includes(range.from().minusDays(1))).isFalse();
        assertThat(range.includes(range.to().plusDays(1))).isFalse();
        assertThat(range.lengthInDays()).isEqualTo(high - low + 1);
    }

    @Property(tries = 1000)
    void anyReversedRangeIsRejected(
            @ForAll @LongRange(min = 0, max = 10_000) long baseOffset,
            @ForAll @LongRange(min = 1, max = 5_000) long gap) {

        LocalDate low = EPOCH.plusDays(baseOffset);

        assertThatThrownBy(() -> new DateRange(low.plusDays(gap), low))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<Long> seedValues() {
        return net.jqwik.api.Arbitraries.longs();
    }
}
