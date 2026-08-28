package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain unit tests for {@link Money} arithmetic (PRD §7: "Money arithmetic"). */
class MoneyTest {

    @Test
    void plusAddsMinorUnits() {
        assertThat(Money.ofMinor(100).plus(Money.ofMinor(250))).isEqualTo(Money.ofMinor(350));
    }

    @Test
    void minusSubtractsMinorUnits() {
        assertThat(Money.ofMinor(500).minus(Money.ofMinor(200))).isEqualTo(Money.ofMinor(300));
    }

    @Test
    void negateFlipsSign() {
        assertThat(Money.ofMinor(420_000).negate()).isEqualTo(Money.ofMinor(-420_000));
        assertThat(Money.ofMinor(-100).negate()).isEqualTo(Money.ofMinor(100));
    }

    @Test
    void zeroIsIdentifiedCorrectly() {
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.ofMinor(1).isZero()).isFalse();
        assertThat(Money.ofMinor(0).isZero()).isTrue();
    }

    @Test
    void positiveAndNegativeAreIdentifiedCorrectly() {
        assertThat(Money.ofMinor(1).isPositive()).isTrue();
        assertThat(Money.ofMinor(-1).isPositive()).isFalse();
        assertThat(Money.ofMinor(-1).isNegative()).isTrue();
        assertThat(Money.ofMinor(1).isNegative()).isFalse();
    }

    @Test
    void sumOfCollectionAddsAllAmounts() {
        Money total = Money.sum(java.util.List.of(Money.ofMinor(100), Money.ofMinor(-40), Money.ofMinor(-60)));
        assertThat(total).isEqualTo(Money.ZERO);
    }

    @Test
    void sumOfEmptyCollectionIsZero() {
        assertThat(Money.sum(java.util.List.of())).isEqualTo(Money.ZERO);
    }

    @Test
    void compareToOrdersByMinorUnits() {
        assertThat(Money.ofMinor(100).compareTo(Money.ofMinor(200))).isNegative();
        assertThat(Money.ofMinor(200).compareTo(Money.ofMinor(100))).isPositive();
        assertThat(Money.ofMinor(100).compareTo(Money.ofMinor(100))).isZero();
    }

    @Test
    void additionOverflowThrowsRatherThanWrapping() {
        assertThatThrownBy(() -> Money.ofMinor(Long.MAX_VALUE).plus(Money.ofMinor(1)))
                .isInstanceOf(ArithmeticException.class);
    }
}
