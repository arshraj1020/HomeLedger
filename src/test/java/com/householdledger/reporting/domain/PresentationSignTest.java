package com.householdledger.reporting.domain;

import com.householdledger.ledger.domain.AccountType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one place an account-type sign convention is applied (PRD §10).
 *
 * <p>These assertions are the guard against the risk the PRD names: getting
 * a sign backwards would make every liability and every income figure read
 * inverted, and <b>nothing downstream would catch it</b> — the raw signed
 * totals would still sum to zero and the trial balance would still pass.
 * Only a test that states the expected human-facing figure can catch it.
 */
class PresentationSignTest {

    @Test
    void debitNormalAccountsAreDisplayedUnchanged() {
        // ₹4,200 of groceries is stored +420000 and read as +420000.
        assertThat(PresentationSign.forDisplay(AccountType.ASSET, 420_000)).isEqualTo(420_000);
        assertThat(PresentationSign.forDisplay(AccountType.EXPENSE, 420_000)).isEqualTo(420_000);
    }

    @Test
    void debitNormalAccountsKeepALegitimateNegative() {
        // An overdrawn cash account genuinely is negative; presentation must
        // not hide that.
        assertThat(PresentationSign.forDisplay(AccountType.ASSET, -5_000)).isEqualTo(-5_000);
    }

    @Test
    void creditNormalAccountsAreDisplayedNegated() {
        // A ₹4,200 card balance is stored -420000 (the money came *from* the
        // card) and a member asking "what's on the card?" expects 4,200.
        assertThat(PresentationSign.forDisplay(AccountType.LIABILITY, -420_000)).isEqualTo(420_000);
        assertThat(PresentationSign.forDisplay(AccountType.INCOME, -5_000_000)).isEqualTo(5_000_000);
        assertThat(PresentationSign.forDisplay(AccountType.EQUITY, -100)).isEqualTo(100);
    }

    @Test
    void aCreditNormalAccountInDebitReadsNegative() {
        // Overpaying a credit card leaves it in debit; that reads as -500.
        assertThat(PresentationSign.forDisplay(AccountType.LIABILITY, 500)).isEqualTo(-500);
    }

    @Test
    void zeroIsZeroForEveryAccountType() {
        for (AccountType type : AccountType.values()) {
            assertThat(PresentationSign.forDisplay(type, 0L)).isZero();
        }
    }

    @Test
    void exactlyTheCreditNormalTypesAreFlipped() {
        assertThat(PresentationSign.isFlipped(AccountType.ASSET)).isFalse();
        assertThat(PresentationSign.isFlipped(AccountType.EXPENSE)).isFalse();
        assertThat(PresentationSign.isFlipped(AccountType.LIABILITY)).isTrue();
        assertThat(PresentationSign.isFlipped(AccountType.INCOME)).isTrue();
        assertThat(PresentationSign.isFlipped(AccountType.EQUITY)).isTrue();
    }

    @Test
    void isFlippedAgreesWithTheConversionForEveryType() {
        // Guards the two from drifting apart: a client labels a column from
        // isFlipped and reads the number from forDisplay.
        for (AccountType type : AccountType.values()) {
            long converted = PresentationSign.forDisplay(type, 100L);
            assertThat(converted == -100L).isEqualTo(PresentationSign.isFlipped(type));
        }
    }

    @Test
    void negatingTheExtremeValueThrowsRatherThanWrapping() {
        // Long.MIN_VALUE has no positive counterpart. Wrapping a balance back
        // to a negative number is the worst possible failure for a ledger, so
        // this fails loudly instead — consistent with Money (PRD §3.3).
        assertThatThrownBy(() -> PresentationSign.forDisplay(AccountType.LIABILITY, Long.MIN_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void debitNormalIsSafeEvenAtTheExtremeValue() {
        // No negation happens, so there is nothing to overflow.
        assertThat(PresentationSign.forDisplay(AccountType.ASSET, Long.MIN_VALUE)).isEqualTo(Long.MIN_VALUE);
    }
}
