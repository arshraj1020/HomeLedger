package com.householdledger.ledger.domain;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests (PRD §7, §9): generate random sets of postings and
 * assert that balanced sets are always accepted and unbalanced sets are
 * always rejected. jqwik's default is 1000 generated cases per property
 * (tries = 1000 below, made explicit rather than relying on the default so
 * this stays true even if jqwik's global default ever changes) — this is
 * the "headline test" the PRD calls out as worth featuring in the README,
 * and the acceptance criterion in PRD §9 ("property tests run at least 1000
 * generated cases and pass").
 */
class TransactionPropertyTest {

    @Property(tries = 1000)
    void balancedRandomPostingSetsAreAlwaysAccepted(
            @ForAll @IntRange(min = 2, max = 8) int legCount,
            @ForAll("seeds") long seed) {

        java.util.Random random = new java.util.Random(seed);
        List<Posting> postings = randomBalancedPostings(random, legCount);

        Transaction txn = Transaction.of(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), "property test", UUID.randomUUID(), postings);

        Money total = Money.ZERO;
        for (Posting p : txn.postings()) {
            total = total.plus(p.amount());
        }
        assertThat(total).isEqualTo(Money.ZERO);
    }

    @Property(tries = 1000)
    void unbalancedRandomPostingSetsAreAlwaysRejected(
            @ForAll @IntRange(min = 2, max = 8) int legCount,
            @ForAll("seeds") long seed,
            @ForAll @LongRange(min = 1, max = 10_000_00) long skew) {

        java.util.Random random = new java.util.Random(seed);
        List<Posting> postings = randomBalancedPostings(random, legCount);
        // Perturb the last leg so the set no longer sums to zero, by
        // construction (skew is always > 0).
        Posting last = postings.remove(postings.size() - 1);
        postings.add(Posting.of(last.accountId(), last.amount().plus(Money.ofMinor(skew))));

        assertThatThrownBy(() -> Transaction.of(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), "property test", UUID.randomUUID(), postings))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    /**
     * Builds a random but always-balanced posting list: legCount-1 random
     * nonzero legs against distinct accounts, plus a final leg that exactly
     * offsets their sum (and is itself nonzero — resampled if it would land
     * on zero, which is astronomically rare with a 64-bit range but excluded
     * for correctness).
     */
    private List<Posting> randomBalancedPostings(java.util.Random random, int legCount) {
        List<Posting> legs = new ArrayList<>(legCount);
        long runningTotal = 0L;
        for (int i = 0; i < legCount - 1; i++) {
            long amount = randomNonZeroAmount(random);
            legs.add(Posting.of(UUID.randomUUID(), Money.ofMinor(amount)));
            runningTotal += amount;
        }
        long finalAmount = -runningTotal;
        if (finalAmount == 0L) {
            finalAmount = randomNonZeroAmount(random);
            // Compensate the very first leg so the set still balances.
            Posting first = legs.remove(0);
            legs.add(0, Posting.of(first.accountId(), first.amount().minus(Money.ofMinor(finalAmount))));
        }
        legs.add(Posting.of(UUID.randomUUID(), Money.ofMinor(finalAmount)));
        return legs;
    }

    private long randomNonZeroAmount(java.util.Random random) {
        long amount;
        do {
            amount = random.nextInt(2_000_000) - 1_000_000; // +/- 10,000.00 in minor units
        } while (amount == 0L);
        return amount;
    }
}
