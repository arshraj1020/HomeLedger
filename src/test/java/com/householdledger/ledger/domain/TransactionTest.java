package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain unit tests for {@link Transaction} construction, per PRD §7:
 * "transaction construction rejecting unbalanced posting sets, reversal
 * producing exact inverses."
 */
class TransactionTest {

    private final UUID householdId = UUID.randomUUID();
    private final UUID createdBy = UUID.randomUUID();
    private final UUID groceriesAccountId = UUID.randomUUID();
    private final UUID cardAccountId = UUID.randomUUID();

    @Test
    void validBalancedTwoPostingTransactionConstructsSuccessfully() {
        Transaction txn = Transaction.of(
                UUID.randomUUID(), householdId, LocalDate.now(), "Weekly groceries", createdBy,
                List.of(
                        Posting.of(groceriesAccountId, Money.ofMinor(420_000)),
                        Posting.of(cardAccountId, Money.ofMinor(-420_000))
                ));

        assertThat(txn.postings()).hasSize(2);
        assertThat(txn.amount()).isEqualTo(Money.ofMinor(420_000));
        assertThat(txn.isReversal()).isFalse();
    }

    @Test
    void validBalancedSplitTransactionConstructsSuccessfully() {
        UUID electricityAccountId = UUID.randomUUID();
        Transaction txn = Transaction.of(
                UUID.randomUUID(), householdId, LocalDate.now(), "Combined utility bill", createdBy,
                List.of(
                        Posting.of(groceriesAccountId, Money.ofMinor(10_000)),
                        Posting.of(electricityAccountId, Money.ofMinor(20_000)),
                        Posting.of(cardAccountId, Money.ofMinor(-30_000))
                ));

        assertThat(txn.postings()).hasSize(3);
    }

    @Test
    void unbalancedTransactionIsRejected() {
        assertThatThrownBy(() -> Transaction.of(
                UUID.randomUUID(), householdId, LocalDate.now(), "Bad txn", createdBy,
                List.of(
                        Posting.of(groceriesAccountId, Money.ofMinor(420_000)),
                        Posting.of(cardAccountId, Money.ofMinor(-410_000))
                )))
                .isInstanceOf(UnbalancedTransactionException.class)
                .hasMessageContaining("10000");
    }

    @Test
    void singlePostingTransactionIsRejected() {
        assertThatThrownBy(() -> Transaction.of(
                UUID.randomUUID(), householdId, LocalDate.now(), "Bad txn", createdBy,
                List.of(Posting.of(groceriesAccountId, Money.ofMinor(420_000)))))
                .isInstanceOf(UnbalancedTransactionException.class)
                .hasMessageContaining("at least two postings");
    }

    @Test
    void zeroPostingTransactionIsRejected() {
        assertThatThrownBy(() -> Transaction.of(
                UUID.randomUUID(), householdId, LocalDate.now(), "Bad txn", createdBy, List.of()))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void zeroAmountPostingIsRejectedAtConstruction() {
        assertThatThrownBy(() -> Posting.of(groceriesAccountId, Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reversalProducesExactSignInversePostings() {
        Transaction original = Transaction.of(
                UUID.randomUUID(), householdId, LocalDate.now(), "Weekly groceries", createdBy,
                List.of(
                        Posting.of(groceriesAccountId, Money.ofMinor(420_000)),
                        Posting.of(cardAccountId, Money.ofMinor(-420_000))
                ));

        UUID reversalId = UUID.randomUUID();
        Transaction reversal = original.reverse(reversalId, LocalDate.now(), "Reversal of Weekly groceries", createdBy);

        assertThat(reversal.id()).isEqualTo(reversalId);
        assertThat(reversal.reversesTransactionId()).isEqualTo(original.id());
        assertThat(reversal.isReversal()).isTrue();
        assertThat(reversal.postings()).hasSize(2);

        for (int i = 0; i < original.postings().size(); i++) {
            Posting originalLeg = original.postings().get(i);
            Posting reversedLeg = reversal.postings().get(i);
            assertThat(reversedLeg.accountId()).isEqualTo(originalLeg.accountId());
            assertThat(reversedLeg.amount()).isEqualTo(originalLeg.amount().negate());
        }

        // The reversal is itself a valid, balanced transaction.
        assertThat(Money.sum(reversal.postings().stream().map(Posting::amount).toList())).isEqualTo(Money.ZERO);
    }

    @Test
    void reversalOfReversalStillProducesBalancedTransactionAtDomainLevel() {
        // Domain layer only guarantees balance; "a reversal cannot itself be
        // reversed" (PRD §FR-4) is a service-layer rule requiring a DB lookup
        // of prior reversal state, exercised in the integration tests instead.
        Transaction original = Transaction.of(
                UUID.randomUUID(), householdId, LocalDate.now(), "Original", createdBy,
                List.of(Posting.of(groceriesAccountId, Money.ofMinor(100)), Posting.of(cardAccountId, Money.ofMinor(-100))));
        Transaction reversal = original.reverse(UUID.randomUUID(), LocalDate.now(), "Reversal", createdBy);
        Transaction reversalOfReversal = reversal.reverse(UUID.randomUUID(), LocalDate.now(), "Reversal of reversal", createdBy);

        assertThat(reversalOfReversal.postings().get(0).amount()).isEqualTo(Money.ofMinor(100));
    }

    @Test
    void postingsListIsImmutable() {
        Transaction txn = Transaction.of(
                UUID.randomUUID(), householdId, LocalDate.now(), "Weekly groceries", createdBy,
                List.of(
                        Posting.of(groceriesAccountId, Money.ofMinor(100)),
                        Posting.of(cardAccountId, Money.ofMinor(-100))
                ));

        assertThatThrownBy(() -> txn.postings().add(Posting.of(groceriesAccountId, Money.ofMinor(1))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
