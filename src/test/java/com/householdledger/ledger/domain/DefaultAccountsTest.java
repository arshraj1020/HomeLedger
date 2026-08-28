package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seed chart of accounts (PRD §FR-2). The two PRD-mandated accounts are
 * asserted by name and type; the expense set is asserted structurally rather
 * than item-by-item, because the PRD deliberately leaves its membership open
 * and pinning every string would make the list painful to adjust without
 * catching any real defect.
 */
class DefaultAccountsTest {

    @Test
    void includesTheCashAssetAccountThePrdRequires() {
        assertThat(DefaultAccounts.seedAccounts())
                .contains(new DefaultAccounts.SeedAccount(AccountType.ASSET, "Cash"));
        assertThat(DefaultAccounts.CASH).isEqualTo("Cash");
    }

    @Test
    void includesTheOpeningBalancesEquityAccountThePrdRequires() {
        // Without an equity counterpart there is no way to open a ledger with
        // existing balances and still satisfy the invariant (PRD §3.2).
        assertThat(DefaultAccounts.seedAccounts())
                .contains(new DefaultAccounts.SeedAccount(AccountType.EQUITY, "Opening Balances"));
        assertThat(DefaultAccounts.OPENING_BALANCES).isEqualTo("Opening Balances");
    }

    @Test
    void includesADefaultExpenseSet() {
        List<DefaultAccounts.SeedAccount> expenses = DefaultAccounts.seedAccounts().stream()
                .filter(seed -> seed.type() == AccountType.EXPENSE)
                .toList();

        assertThat(expenses).hasSizeGreaterThanOrEqualTo(3);
        // The categories the PRD itself names in §2 and §3.1.
        assertThat(expenses.stream().map(DefaultAccounts.SeedAccount::name))
                .contains("Groceries", "Electricity", "School Fees");
    }

    @Test
    void seedNamesAreUniqueCaseInsensitively() {
        // UNIQUE (household_id, name) would reject a duplicate at insert time;
        // catching it here means a bad seed list fails in a fast unit test
        // rather than during household creation.
        List<String> lowercased = DefaultAccounts.seedAccounts().stream()
                .map(seed -> seed.name().toLowerCase(Locale.ROOT))
                .toList();

        assertThat(lowercased).doesNotHaveDuplicates();
    }

    @Test
    void everySeedAccountHasATypeAndANonBlankName() {
        for (DefaultAccounts.SeedAccount seed : DefaultAccounts.seedAccounts()) {
            assertThat(seed.type()).isNotNull();
            assertThat(seed.name()).isNotBlank();
        }
    }

    @Test
    void seedListIsImmutable() {
        assertThatThrownBy(() -> DefaultAccounts.seedAccounts()
                .add(new DefaultAccounts.SeedAccount(AccountType.EXPENSE, "Injected")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void seedListUsesOnlyAssetEquityAndExpenseTypes() {
        // A seeded INCOME or LIABILITY account would be presumptuous — those
        // depend on the household's actual salary sources and cards.
        assertThat(DefaultAccounts.seedAccounts())
                .extracting(DefaultAccounts.SeedAccount::type)
                .containsAnyOf(AccountType.ASSET, AccountType.EQUITY, AccountType.EXPENSE)
                .doesNotContain(AccountType.INCOME, AccountType.LIABILITY);
    }
}
