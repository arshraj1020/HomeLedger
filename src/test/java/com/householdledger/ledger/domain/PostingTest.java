package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Posting} as a value object. {@code TransactionTest}
 * exercises postings incidentally, through transaction construction; this
 * covers the type's own contract — its guards, its inversion, and its value
 * semantics — since a posting is the atom the entire invariant is built on
 * (PRD §3.1, §3.2).
 */
class PostingTest {

    private final UUID accountId = UUID.randomUUID();

    @Test
    void createsPostingWithAccountAndSignedAmount() {
        Posting posting = Posting.of(accountId, Money.ofMinor(420_000));

        assertThat(posting.accountId()).isEqualTo(accountId);
        assertThat(posting.amount()).isEqualTo(Money.ofMinor(420_000));
    }

    @Test
    void acceptsNegativeAmounts() {
        // Postings are signed; the credit side is a normal, valid posting.
        Posting posting = Posting.of(accountId, Money.ofMinor(-420_000));
        assertThat(posting.amount().isNegative()).isTrue();
    }

    @Test
    void zeroAmountIsRejected() {
        assertThatThrownBy(() -> Posting.of(accountId, Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be zero");
    }

    @Test
    void nullAccountIdIsRejected() {
        assertThatThrownBy(() -> Posting.of(null, Money.ofMinor(100)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("accountId");
    }

    @Test
    void nullAmountIsRejected() {
        assertThatThrownBy(() -> Posting.of(accountId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void invertedFlipsSignAndKeepsAccount() {
        Posting original = Posting.of(accountId, Money.ofMinor(420_000));
        Posting inverted = original.inverted();

        assertThat(inverted.accountId()).isEqualTo(accountId);
        assertThat(inverted.amount()).isEqualTo(Money.ofMinor(-420_000));
    }

    @Test
    void invertingTwiceReturnsToTheOriginalAmount() {
        Posting original = Posting.of(accountId, Money.ofMinor(-777));
        assertThat(original.inverted().inverted().amount()).isEqualTo(original.amount());
    }

    @Test
    void invertedDoesNotMutateTheOriginal() {
        Posting original = Posting.of(accountId, Money.ofMinor(100));
        original.inverted();
        assertThat(original.amount()).isEqualTo(Money.ofMinor(100));
    }

    @Test
    void equalityIsByAccountAndAmount() {
        Posting first = Posting.of(accountId, Money.ofMinor(100));
        Posting identical = Posting.of(accountId, Money.ofMinor(100));
        Posting differentAmount = Posting.of(accountId, Money.ofMinor(101));
        Posting differentAccount = Posting.of(UUID.randomUUID(), Money.ofMinor(100));

        assertThat(first).isEqualTo(identical);
        assertThat(first).hasSameHashCodeAs(identical);
        assertThat(first).isNotEqualTo(differentAmount);
        assertThat(first).isNotEqualTo(differentAccount);
    }

    @Test
    void equalsHandlesSelfAndForeignTypes() {
        Posting posting = Posting.of(accountId, Money.ofMinor(100));

        assertThat(posting).isEqualTo(posting);
        assertThat(posting).isNotEqualTo("not a posting");
        assertThat(posting).isNotEqualTo(null);
    }

    @Test
    void toStringContainsAccountAndAmount() {
        Posting posting = Posting.of(accountId, Money.ofMinor(420_000));

        assertThat(posting.toString())
                .contains(accountId.toString())
                .contains("4200.00");
    }
}
