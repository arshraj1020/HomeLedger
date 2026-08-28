package com.householdledger.ledger.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for the {@link Account} domain snapshot. The validation in its
 * constructor is the only thing standing between a malformed account and the
 * rest of the ledger, since {@code Account} is what
 * {@link com.householdledger.ledger.api.LedgerService} hands to other modules
 * (PRD §6.1) — so each guard is asserted explicitly rather than assumed.
 */
class AccountTest {

    private final UUID id = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    @Test
    void constructsWithAllFieldsAndExposesThem() {
        Account account = new Account(id, householdId, AccountType.ASSET, "HDFC Savings", true);

        assertThat(account.id()).isEqualTo(id);
        assertThat(account.householdId()).isEqualTo(householdId);
        assertThat(account.type()).isEqualTo(AccountType.ASSET);
        assertThat(account.name()).isEqualTo("HDFC Savings");
        assertThat(account.active()).isTrue();
    }

    @Test
    void deactivatedAccountReportsInactive() {
        Account account = new Account(id, householdId, AccountType.LIABILITY, "Closed Card", false);
        assertThat(account.active()).isFalse();
    }

    @Test
    void nullIdIsRejected() {
        assertThatThrownBy(() -> new Account(null, householdId, AccountType.ASSET, "Cash", true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }

    @Test
    void nullHouseholdIdIsRejected() {
        assertThatThrownBy(() -> new Account(id, null, AccountType.ASSET, "Cash", true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("householdId");
    }

    @Test
    void nullTypeIsRejected() {
        assertThatThrownBy(() -> new Account(id, householdId, null, "Cash", true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");
    }

    @Test
    void nullNameIsRejected() {
        assertThatThrownBy(() -> new Account(id, householdId, AccountType.ASSET, null, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> new Account(id, householdId, AccountType.ASSET, "   ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void emptyNameIsRejected() {
        assertThatThrownBy(() -> new Account(id, householdId, AccountType.ASSET, "", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nameWithSurroundingWhitespaceButRealContentIsAccepted() {
        // Deliberately NOT trimmed by the domain — naming policy belongs to the
        // account-management layer (Phase 3), not here. Documents that choice.
        assertThatNoException().isThrownBy(
                () -> new Account(id, householdId, AccountType.EXPENSE, " Groceries ", true));
    }

    @Test
    void equalityIsByIdAlone() {
        Account first = new Account(id, householdId, AccountType.ASSET, "Cash", true);
        Account sameIdDifferentEverythingElse =
                new Account(id, UUID.randomUUID(), AccountType.EXPENSE, "Groceries", false);
        Account differentId = new Account(UUID.randomUUID(), householdId, AccountType.ASSET, "Cash", true);

        assertThat(first).isEqualTo(sameIdDifferentEverythingElse);
        assertThat(first).hasSameHashCodeAs(sameIdDifferentEverythingElse);
        assertThat(first).isNotEqualTo(differentId);
    }

    @Test
    void equalsHandlesSelfAndForeignTypes() {
        Account account = new Account(id, householdId, AccountType.ASSET, "Cash", true);

        assertThat(account).isEqualTo(account);
        assertThat(account).isNotEqualTo("not an account");
        assertThat(account).isNotEqualTo(null);
    }

    @Test
    void toStringContainsIdentifyingFields() {
        Account account = new Account(id, householdId, AccountType.LIABILITY, "HDFC Card", true);

        assertThat(account.toString())
                .contains(id.toString())
                .contains("LIABILITY")
                .contains("HDFC Card");
    }
}
