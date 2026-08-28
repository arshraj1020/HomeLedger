package com.householdledger.ledger.internal;

import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AccountEntity}. Lives in the same package as the
 * class under test because {@code AccountEntity} is package-private by
 * design (PRD §6.1: persistence types must not escape
 * {@code ledger.internal}); a test in this package exercises it without
 * widening its visibility, and {@code ModuleBoundaryArchTest} excludes test
 * classes from its import, so the boundary rule is unaffected.
 *
 * <p>The behaviour here is genuinely worth asserting rather than merely
 * being coverage: {@code toDomain()} is the mapping every other module sees
 * accounts through, and the activate/deactivate/rename methods are the
 * account lifecycle from PRD §FR-2 ("Accounts are never deleted",
 * "Deactivated accounts reject new postings").
 */
class AccountEntityTest {

    private final UUID id = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    private AccountEntity newAccount() {
        return new AccountEntity(id, householdId, AccountType.ASSET, "HDFC Savings");
    }

    @Test
    void newAccountIsActiveByDefault() {
        // PRD §FR-2: accounts are created usable; deactivation is an explicit act.
        assertThat(newAccount().isActive()).isTrue();
    }

    @Test
    void exposesConstructorFields() {
        AccountEntity account = newAccount();

        assertThat(account.getId()).isEqualTo(id);
        assertThat(account.getHouseholdId()).isEqualTo(householdId);
        assertThat(account.getType()).isEqualTo(AccountType.ASSET);
        assertThat(account.getName()).isEqualTo("HDFC Savings");
    }

    @Test
    void renameChangesOnlyTheName() {
        AccountEntity account = newAccount();
        account.rename("HDFC Savings (joint)");

        assertThat(account.getName()).isEqualTo("HDFC Savings (joint)");
        assertThat(account.getId()).isEqualTo(id);
        assertThat(account.getType()).isEqualTo(AccountType.ASSET);
        assertThat(account.isActive()).isTrue();
    }

    @Test
    void deactivateMarksAccountInactive() {
        AccountEntity account = newAccount();
        account.deactivate();

        assertThat(account.isActive()).isFalse();
    }

    @Test
    void activateRestoresAnInactiveAccount() {
        AccountEntity account = newAccount();
        account.deactivate();
        account.activate();

        assertThat(account.isActive()).isTrue();
    }

    @Test
    void deactivateIsIdempotent() {
        AccountEntity account = newAccount();
        account.deactivate();
        account.deactivate();

        assertThat(account.isActive()).isFalse();
    }

    @Test
    void toDomainMapsEveryFieldOntoTheDomainSnapshot() {
        Account domain = newAccount().toDomain();

        assertThat(domain.id()).isEqualTo(id);
        assertThat(domain.householdId()).isEqualTo(householdId);
        assertThat(domain.type()).isEqualTo(AccountType.ASSET);
        assertThat(domain.name()).isEqualTo("HDFC Savings");
        assertThat(domain.active()).isTrue();
    }

    @Test
    void toDomainReflectsDeactivation() {
        AccountEntity account = newAccount();
        account.deactivate();

        assertThat(account.toDomain().active()).isFalse();
    }

    @Test
    void toDomainReflectsRename() {
        AccountEntity account = newAccount();
        account.rename("Renamed");

        assertThat(account.toDomain().name()).isEqualTo("Renamed");
    }

    @Test
    void eachAccountTypeMapsThrough() {
        for (AccountType type : AccountType.values()) {
            AccountEntity account = new AccountEntity(UUID.randomUUID(), householdId, type, "Account " + type);
            assertThat(account.toDomain().type()).isEqualTo(type);
        }
    }
}
