package com.householdledger.ledger;

import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.*;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.ledger.domain.DefaultAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Account management against real PostgreSQL 16 (PRD §FR-2).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AccountServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("household_ledger_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private MemberProvisioningService provisioningService;

    private Household household;
    private UUID memberId;

    @BeforeEach
    void provision() {
        household = provisioningService.createHousehold("Test Household");
        memberId = provisioningService.registerMember(
                household.id(), "Papa", "papa+" + UUID.randomUUID() + "@example.com",
                "correct-horse-battery-staple", Role.ADMIN).id();
    }

    private UUID seededAccountId(String name) {
        return accountService.listAccounts(household.id()).stream()
                .filter(a -> a.name().equals(name))
                .findFirst().orElseThrow().id();
    }

    // ---------- seeding (PRD §FR-2) ----------

    @Test
    void householdCreationSeedsTheDefaultChartOfAccounts() {
        List<Account> accounts = accountService.listAccounts(household.id());

        assertThat(accounts).hasSize(DefaultAccounts.seedAccounts().size());
        assertThat(accounts).extracting(Account::name)
                .contains("Cash", "Opening Balances", "Groceries", "Electricity", "School Fees");
    }

    @Test
    void seededCashIsAnAssetAndOpeningBalancesIsEquity() {
        List<Account> accounts = accountService.listAccounts(household.id());

        assertThat(accounts).filteredOn(a -> a.name().equals("Cash"))
                .singleElement().extracting(Account::type).isEqualTo(AccountType.ASSET);
        assertThat(accounts).filteredOn(a -> a.name().equals("Opening Balances"))
                .singleElement().extracting(Account::type).isEqualTo(AccountType.EQUITY);
    }

    @Test
    void seededAccountsStartActive() {
        assertThat(accountService.listAccounts(household.id())).allMatch(Account::active);
    }

    @Test
    void seedingIsIdempotent() {
        int before = accountService.listAccounts(household.id()).size();

        accountService.seedDefaultAccounts(household.id());

        // Must not collide with UNIQUE (household_id, name) or duplicate rows.
        assertThat(accountService.listAccounts(household.id())).hasSize(before);
    }

    @Test
    void eachHouseholdGetsItsOwnSeededAccounts() {
        Household other = provisioningService.createHousehold("Other Household");

        assertThat(accountService.listAccounts(other.id())).hasSameSizeAs(
                accountService.listAccounts(household.id()));
        // Same names, different rows — UNIQUE is per household, not global.
        assertThat(seededAccountId("Cash"))
                .isNotEqualTo(accountService.listAccounts(other.id()).stream()
                        .filter(a -> a.name().equals("Cash")).findFirst().orElseThrow().id());
    }

    // ---------- create ----------

    @Test
    void createsAnAccountOfEachType() {
        for (AccountType type : AccountType.values()) {
            Account created = accountService.createAccount(household.id(), type, "Custom " + type);
            assertThat(created.type()).isEqualTo(type);
            assertThat(created.active()).isTrue();
        }
    }

    @Test
    void duplicateNameIsRejected() {
        assertThatThrownBy(() -> accountService.createAccount(household.id(), AccountType.EXPENSE, "Groceries"))
                .isInstanceOf(AccountNameAlreadyExistsException.class);
    }

    @Test
    void duplicateNameIsRejectedCaseInsensitively() {
        assertThatThrownBy(() -> accountService.createAccount(household.id(), AccountType.EXPENSE, "GROCERIES"))
                .isInstanceOf(AccountNameAlreadyExistsException.class);
    }

    @Test
    void theSameNameIsAllowedInADifferentHousehold() {
        Household other = provisioningService.createHousehold("Other Household");
        accountService.createAccount(household.id(), AccountType.ASSET, "HDFC Savings");

        assertThatCode(() -> accountService.createAccount(other.id(), AccountType.ASSET, "HDFC Savings"))
                .doesNotThrowAnyException();
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> accountService.createAccount(household.id(), AccountType.ASSET, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accountService.createAccount(household.id(), AccountType.ASSET, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nameIsTrimmed() {
        Account created = accountService.createAccount(household.id(), AccountType.ASSET, "  HDFC Savings  ");
        assertThat(created.name()).isEqualTo("HDFC Savings");
    }

    // ---------- rename ----------

    @Test
    void renamesAnAccount() {
        UUID cash = seededAccountId("Cash");

        Account renamed = accountService.renameAccount(household.id(), cash, "Cash in wallet");

        assertThat(renamed.name()).isEqualTo("Cash in wallet");
        assertThat(renamed.id()).isEqualTo(cash);
        assertThat(renamed.type()).isEqualTo(AccountType.ASSET);
    }

    @Test
    void renamingToAnExistingNameIsRejected() {
        UUID cash = seededAccountId("Cash");

        assertThatThrownBy(() -> accountService.renameAccount(household.id(), cash, "Groceries"))
                .isInstanceOf(AccountNameAlreadyExistsException.class);
    }

    @Test
    void renamingAnAccountToItsOwnNameIsANoOpNotAConflict() {
        UUID cash = seededAccountId("Cash");

        assertThatCode(() -> accountService.renameAccount(household.id(), cash, "Cash"))
                .doesNotThrowAnyException();
    }

    @Test
    void renamingAnAccountFromAnotherHouseholdIsNotFound() {
        Household other = provisioningService.createHousehold("Other Household");
        UUID otherCash = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Cash")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> accountService.renameAccount(household.id(), otherCash, "Stolen"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void renamingAnUnknownAccountIsNotFound() {
        assertThatThrownBy(() -> accountService.renameAccount(household.id(), UUID.randomUUID(), "Ghost"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    // ---------- deactivate / reactivate ----------

    @Test
    void deactivatesAndReactivatesAnAccount() {
        UUID cash = seededAccountId("Cash");

        assertThat(accountService.setAccountActive(household.id(), cash, false).active()).isFalse();
        assertThat(accountService.setAccountActive(household.id(), cash, true).active()).isTrue();
    }

    @Test
    void aDeactivatedAccountRejectsNewPostings() {
        UUID cash = seededAccountId("Cash");
        UUID groceries = seededAccountId("Groceries");
        accountService.setAccountActive(household.id(), cash, false);

        assertThatThrownBy(() -> ledgerService.recordTransaction(
                household.id(), LocalDate.now(), "Should fail", memberId,
                List.of(new PostingLine(groceries, 10_000), new PostingLine(cash, -10_000))))
                .isInstanceOf(InactiveAccountException.class);
    }

    @Test
    void aDeactivatedAccountRemainsInHistoricalQueries() {
        // PRD §FR-2: "Deactivated accounts reject new postings but remain in
        // historical queries." Record first, then deactivate.
        UUID cash = seededAccountId("Cash");
        UUID groceries = seededAccountId("Groceries");
        ledgerService.recordTransaction(household.id(), LocalDate.now(), "Weekly groceries", memberId,
                List.of(new PostingLine(groceries, 42_000), new PostingLine(cash, -42_000)));

        accountService.setAccountActive(household.id(), cash, false);

        assertThat(accountService.listAccounts(household.id())).extracting(Account::name).contains("Cash");
        // Its historical postings still count toward the derived balance.
        assertThat(ledgerService.accountBalanceMinor(household.id(), cash, null)).isEqualTo(-42_000);
    }

    @Test
    void reactivatedAccountAcceptsPostingsAgain() {
        UUID cash = seededAccountId("Cash");
        UUID groceries = seededAccountId("Groceries");
        accountService.setAccountActive(household.id(), cash, false);
        accountService.setAccountActive(household.id(), cash, true);

        assertThatCode(() -> ledgerService.recordTransaction(
                household.id(), LocalDate.now(), "Now fine", memberId,
                List.of(new PostingLine(groceries, 10_000), new PostingLine(cash, -10_000))))
                .doesNotThrowAnyException();
    }

    @Test
    void deactivatingAnAccountFromAnotherHouseholdIsNotFound() {
        Household other = provisioningService.createHousehold("Other Household");
        UUID otherCash = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Cash")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> accountService.setAccountActive(household.id(), otherCash, false))
                .isInstanceOf(AccountNotFoundException.class);
    }

    // ---------- list ----------

    @Test
    void listReturnsDeactivatedAccountsToo() {
        UUID cash = seededAccountId("Cash");
        accountService.setAccountActive(household.id(), cash, false);

        assertThat(accountService.listAccounts(household.id()))
                .anyMatch(a -> a.id().equals(cash) && !a.active());
    }

    @Test
    void listIsScopedToTheHousehold() {
        Household other = provisioningService.createHousehold("Other Household");
        accountService.createAccount(other.id(), AccountType.ASSET, "Other-only account");

        assertThat(accountService.listAccounts(household.id()))
                .extracting(Account::name).doesNotContain("Other-only account");
    }
}
