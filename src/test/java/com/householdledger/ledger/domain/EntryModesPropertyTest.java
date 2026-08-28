package com.householdledger.ledger.domain;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the entry-mode translation (PRD §7, §9 — "property
 * tests run at least 1000 generated cases and pass").
 *
 * <p>The property that matters: <b>however a user describes a movement of
 * money, the postings produced sum to exactly zero.</b> This is the
 * user-facing half of the §3.2 invariant. The Phase 1 properties prove the
 * ledger *rejects* unbalanced sets; these prove the entry modes never
 * *construct* one in the first place — so a well-formed request can never
 * reach, let alone trip, the database trigger.
 *
 * <p>Amounts are bounded to a range far below {@code Long.MAX_VALUE} so that
 * summing several allocations cannot overflow: {@code Money} throws on
 * overflow by design, and a generated overflow would be testing arithmetic
 * limits rather than the translation logic.
 */
class EntryModesPropertyTest {

    private static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;

    @Property(tries = 1000)
    void simpleAlwaysProducesABalancedTwoPostingSet(
            @ForAll @LongRange(min = 1, max = MAX_AMOUNT_MINOR) long amountMinor) {

        List<Posting> postings = EntryModes.simple(
                UUID.randomUUID(), UUID.randomUUID(), Money.ofMinor(amountMinor));

        assertThat(postings).hasSize(2);
        assertThat(Money.sum(postings.stream().map(Posting::amount).toList())).isEqualTo(Money.ZERO);
    }

    @Property(tries = 1000)
    void simpleAlwaysBuildsAValidTransaction(
            @ForAll @LongRange(min = 1, max = MAX_AMOUNT_MINOR) long amountMinor) {

        // The end-to-end property: the domain aggregate accepts whatever the
        // entry mode produces, for every amount.
        List<Posting> postings = EntryModes.simple(
                UUID.randomUUID(), UUID.randomUUID(), Money.ofMinor(amountMinor));

        Transaction transaction = Transaction.of(UUID.randomUUID(), UUID.randomUUID(),
                java.time.LocalDate.now(), "property", UUID.randomUUID(), postings);

        assertThat(transaction.amount()).isEqualTo(Money.ofMinor(amountMinor));
    }

    @Property(tries = 1000)
    void splitAlwaysProducesABalancedSetOfDestinationsPlusOne(
            @ForAll @IntRange(min = 1, max = 8) int destinationCount,
            @ForAll("seeds") long seed) {

        java.util.Random random = new java.util.Random(seed);
        List<EntryModes.SplitAllocation> allocations = new ArrayList<>(destinationCount);
        long expectedTotal = 0L;

        for (int i = 0; i < destinationCount; i++) {
            long amount = 1L + Math.floorMod(random.nextLong(), 10_000_000L);
            allocations.add(new EntryModes.SplitAllocation(UUID.randomUUID(), Money.ofMinor(amount)));
            expectedTotal += amount;
        }

        List<Posting> postings = EntryModes.split(UUID.randomUUID(), allocations);

        assertThat(postings).hasSize(destinationCount + 1);
        assertThat(Money.sum(postings.stream().map(Posting::amount).toList())).isEqualTo(Money.ZERO);
        // The source leg always carries the exact negated total — no rounding,
        // no remainder (PRD §3.3).
        assertThat(postings.get(postings.size() - 1).amount()).isEqualTo(Money.ofMinor(-expectedTotal));
    }

    @Property(tries = 1000)
    void splitOfAnySizeBuildsAValidTransaction(
            @ForAll @IntRange(min = 1, max = 8) int destinationCount,
            @ForAll @LongRange(min = 1, max = 10_000_000L) long perDestination) {

        List<EntryModes.SplitAllocation> allocations = new ArrayList<>(destinationCount);
        for (int i = 0; i < destinationCount; i++) {
            allocations.add(new EntryModes.SplitAllocation(UUID.randomUUID(), Money.ofMinor(perDestination)));
        }

        List<Posting> postings = EntryModes.split(UUID.randomUUID(), allocations);

        Transaction transaction = Transaction.of(UUID.randomUUID(), UUID.randomUUID(),
                java.time.LocalDate.now(), "property", UUID.randomUUID(), postings);

        assertThat(transaction.postings()).hasSize(destinationCount + 1);
    }

    @Property(tries = 1000)
    void reversingAnEntryModeTransactionRestoresTheOriginalAmounts(
            @ForAll @LongRange(min = 1, max = MAX_AMOUNT_MINOR) long amountMinor) {

        // Ties Phase 4's entry modes to Phase 1's reversal guarantee
        // (PRD §FR-4): the inverse of the inverse is the original.
        List<Posting> postings = EntryModes.simple(
                UUID.randomUUID(), UUID.randomUUID(), Money.ofMinor(amountMinor));
        Transaction original = Transaction.of(UUID.randomUUID(), UUID.randomUUID(),
                java.time.LocalDate.now(), "property", UUID.randomUUID(), postings);

        Transaction reversal = original.reverse(UUID.randomUUID(), java.time.LocalDate.now(),
                "reversal", UUID.randomUUID());

        for (int i = 0; i < original.postings().size(); i++) {
            assertThat(reversal.postings().get(i).amount())
                    .isEqualTo(original.postings().get(i).amount().negate());
        }
        assertThat(Money.sum(reversal.postings().stream().map(Posting::amount).toList()))
                .isEqualTo(Money.ZERO);
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }
}
