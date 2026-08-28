package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the normal-balance side of each account type (PRD §3.1's
 * classification table). These are accounting facts, not implementation
 * detail: getting one wrong would silently invert how a balance reads at the
 * reporting layer, which is precisely the confusion PRD §10 flags as a
 * medium risk ("Account-type sign conventions confuse the implementation").
 */
class AccountTypeTest {

    @Test
    void assetsAndExpensesHaveDebitNormalBalance() {
        assertThat(AccountType.ASSET.normalBalance()).isEqualTo(AccountType.NormalBalance.DEBIT);
        assertThat(AccountType.EXPENSE.normalBalance()).isEqualTo(AccountType.NormalBalance.DEBIT);
    }

    @Test
    void liabilitiesIncomeAndEquityHaveCreditNormalBalance() {
        assertThat(AccountType.LIABILITY.normalBalance()).isEqualTo(AccountType.NormalBalance.CREDIT);
        assertThat(AccountType.INCOME.normalBalance()).isEqualTo(AccountType.NormalBalance.CREDIT);
        assertThat(AccountType.EQUITY.normalBalance()).isEqualTo(AccountType.NormalBalance.CREDIT);
    }

    @Test
    void thereAreExactlyFiveAccountTypes() {
        // PRD §3.1 defines five. A sixth appearing without a deliberate
        // decision would mean the schema CHECK constraint and this enum have
        // drifted apart.
        assertThat(AccountType.values()).hasSize(5);
    }

    @Test
    void everyTypeDeclaresANormalBalance() {
        for (AccountType type : AccountType.values()) {
            assertThat(type.normalBalance()).isNotNull();
        }
    }
}
