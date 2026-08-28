package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the three entry modes of PRD §FR-3.
 *
 * <p>The sign convention is asserted explicitly rather than left implied:
 * getting it backwards would make every expense look like income and every
 * balance read inverted, and no downstream check would catch it — the
 * postings would still sum to zero.
 */
class EntryModesTest {

    private final UUID cash = UUID.randomUUID();
    private final UUID card = UUID.randomUUID();
    private final UUID groceries = UUID.randomUUID();
    private final UUID electricity = UUID.randomUUID();

    private static Money sum(List<Posting> postings) {
        return Money.sum(postings.stream().map(Posting::amount).toList());
    }

    // ---------- simple ----------

    @Test
    void simpleProducesTwoBalancedPostings() {
        List<Posting> postings = EntryModes.simple(card, groceries, Money.ofMinor(420_000));

        assertThat(postings).hasSize(2);
        assertThat(sum(postings)).isEqualTo(Money.ZERO);
    }

    @Test
    void simpleDebitsTheDestinationAndCreditsTheSource() {
        // Matches PRD §6.4's worked example exactly: groceries +420000,
        // card -420000.
        List<Posting> postings = EntryModes.simple(card, groceries, Money.ofMinor(420_000));

        assertThat(postings.get(0).accountId()).isEqualTo(groceries);
        assertThat(postings.get(0).amount()).isEqualTo(Money.ofMinor(420_000));
        assertThat(postings.get(1).accountId()).isEqualTo(card);
        assertThat(postings.get(1).amount()).isEqualTo(Money.ofMinor(-420_000));
    }

    @Test
    void simpleRejectsANonPositiveAmount() {
        assertThatThrownBy(() -> EntryModes.simple(card, groceries, Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
        assertThatThrownBy(() -> EntryModes.simple(card, groceries, Money.ofMinor(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void simpleRejectsTheSameAccountOnBothSides() {
        assertThatThrownBy(() -> EntryModes.simple(cash, cash, Money.ofMinor(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different accounts");
    }

    @Test
    void simpleRejectsNulls() {
        assertThatThrownBy(() -> EntryModes.simple(null, groceries, Money.ofMinor(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EntryModes.simple(card, null, Money.ofMinor(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EntryModes.simple(card, groceries, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------- split ----------

    @Test
    void splitProducesOnePostingPerDestinationPlusTheSource() {
        List<Posting> postings = EntryModes.split(card, List.of(
                new EntryModes.SplitAllocation(groceries, Money.ofMinor(30_000)),
                new EntryModes.SplitAllocation(electricity, Money.ofMinor(20_000))));

        assertThat(postings).hasSize(3);
        assertThat(sum(postings)).isEqualTo(Money.ZERO);
    }

    @Test
    void splitCreditsTheSourceWithTheExactTotal() {
        // No remainder to allocate — PRD §3.3 notes division is not required
        // in v1, and nothing here divides.
        List<Posting> postings = EntryModes.split(card, List.of(
                new EntryModes.SplitAllocation(groceries, Money.ofMinor(30_000)),
                new EntryModes.SplitAllocation(electricity, Money.ofMinor(20_001))));

        Posting source = postings.get(postings.size() - 1);
        assertThat(source.accountId()).isEqualTo(card);
        assertThat(source.amount()).isEqualTo(Money.ofMinor(-50_001));
    }

    @Test
    void splitWithASingleDestinationIsEquivalentToSimple() {
        List<Posting> split = EntryModes.split(card,
                List.of(new EntryModes.SplitAllocation(groceries, Money.ofMinor(100))));
        List<Posting> simple = EntryModes.simple(card, groceries, Money.ofMinor(100));

        assertThat(split).containsExactlyElementsOf(simple);
    }

    @Test
    void splitRejectsNoDestinations() {
        assertThatThrownBy(() -> EntryModes.split(card, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one destination");
    }

    @Test
    void splitRejectsANonPositiveAllocation() {
        assertThatThrownBy(() -> EntryModes.split(card,
                List.of(new EntryModes.SplitAllocation(groceries, Money.ZERO))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntryModes.split(card,
                List.of(new EntryModes.SplitAllocation(groceries, Money.ofMinor(-5)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitRejectsTheSourceAppearingAsADestination() {
        assertThatThrownBy(() -> EntryModes.split(card, List.of(
                new EntryModes.SplitAllocation(groceries, Money.ofMinor(100)),
                new EntryModes.SplitAllocation(card, Money.ofMinor(50)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not also be a split destination");
    }

    @Test
    void splitAllocationRejectsNulls() {
        assertThatThrownBy(() -> new EntryModes.SplitAllocation(null, Money.ofMinor(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntryModes.SplitAllocation(groceries, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------- raw ----------

    @Test
    void rawPassesASignedPostingListThrough() {
        List<Posting> input = List.of(
                Posting.of(groceries, Money.ofMinor(100)),
                Posting.of(card, Money.ofMinor(-100)));

        assertThat(EntryModes.raw(input)).containsExactlyElementsOf(input);
    }

    @Test
    void rawRejectsFewerThanTwoPostings() {
        assertThatThrownBy(() -> EntryModes.raw(List.of(Posting.of(groceries, Money.ofMinor(100)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two postings");
        assertThatThrownBy(() -> EntryModes.raw(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rawDoesNotItselfEnforceBalance() {
        // Deliberate: balance is Transaction's job (PRD §3.2 layer 1), and
        // raw mode exists to let a caller state arbitrary signed legs.
        List<Posting> unbalanced = List.of(
                Posting.of(groceries, Money.ofMinor(100)),
                Posting.of(card, Money.ofMinor(-99)));

        assertThat(EntryModes.raw(unbalanced)).hasSize(2);
        assertThatThrownBy(() -> Transaction.of(UUID.randomUUID(), UUID.randomUUID(),
                java.time.LocalDate.now(), "unbalanced", UUID.randomUUID(), unbalanced))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void returnedPostingListsAreImmutable() {
        List<Posting> postings = EntryModes.simple(card, groceries, Money.ofMinor(100));

        assertThatThrownBy(() -> postings.add(Posting.of(cash, Money.ofMinor(1))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
