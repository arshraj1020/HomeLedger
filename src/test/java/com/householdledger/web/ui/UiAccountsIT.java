package com.householdledger.web.ui;

import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.ledger.domain.DefaultAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The account screens (PRD §FR-2, §FR-7), including the authorisation split of
 * PRD §FR-1 and the 404-not-403 rule of PRD §9.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class UiAccountsIT {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberProvisioningService provisioningService;
    @Autowired private AccountService accountService;

    private static final String PASSWORD = "correct-horse-battery-staple";

    private Member admin;
    private Member plainMember;
    private Member otherHouseholdAdmin;
    private Account cash;
    private Account otherHouseholdAccount;

    @BeforeEach
    void provision() {
        Household household = provisioningService.createHousehold("Sharma Household");
        admin = provisioningService.registerMember(household.id(), "Papa",
                "papa+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN);
        plainMember = provisioningService.registerMember(household.id(), "Mama",
                "mama+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.MEMBER);

        List<Account> seeded = accountService.seedDefaultAccounts(household.id());
        cash = seeded.stream()
                .filter(account -> account.name().equals(DefaultAccounts.CASH))
                .findFirst().orElseThrow();

        Household other = provisioningService.createHousehold("Other Household");
        otherHouseholdAdmin = provisioningService.registerMember(other.id(), "Neighbour",
                "neighbour+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN);
        otherHouseholdAccount = accountService.createAccount(other.id(), AccountType.ASSET, "Their Safe");
    }

    // ------------------------------------------------------------- list

    @Test
    void theListShowsTheSeededChartOfAccountsGroupedByType() throws Exception {
        mockMvc.perform(get("/accounts").with(UiTestAuth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Assets")))
                .andExpect(content().string(containsString("Expenses")))
                .andExpect(content().string(containsString(DefaultAccounts.CASH)))
                .andExpect(content().string(containsString(DefaultAccounts.OPENING_BALANCES)))
                .andExpect(content().string(containsString("Groceries")));
    }

    /**
     * <p><b>Account names are not household-unique, and must not be used as if
     * they were.</b> {@code createHousehold} seeds every new household with
     * the same default chart (PRD §FR-2), so both households own an account
     * called {@code Cash} and one called {@code Opening Balances} — different
     * rows, different ids, same words. An earlier version of this test
     * asserted that the second household's page did not contain "Opening
     * Balances" and failed, correctly: the page was showing that household its
     * own equity account.
     *
     * <p>So isolation is asserted on account <em>ids</em>, which are unique,
     * with the names used only where one genuinely is unique to a household.
     * The ids appear in the page's own links, so a leak has nowhere to hide,
     * and each direction is asserted both ways round — present for the owner,
     * absent for the other — so neither assertion can pass because ids are
     * missing from the markup altogether.
     */
    @Test
    void oneHouseholdNeverSeesAnothersAccounts() throws Exception {
        String mine = mockMvc.perform(get("/accounts").with(UiTestAuth.as(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String theirs = mockMvc.perform(get("/accounts").with(UiTestAuth.as(otherHouseholdAdmin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // "Their Safe" exists in one household only, so the name carries here.
        assertThat(mine).doesNotContain("Their Safe");
        assertThat(theirs).contains("Their Safe");

        // The ids are the real test: each household sees its own Cash, and
        // neither page carries a single identifier belonging to the other.
        assertThat(mine)
                .contains(cash.id().toString())
                .doesNotContain(otherHouseholdAccount.id().toString());
        assertThat(theirs)
                .contains(otherHouseholdAccount.id().toString())
                .doesNotContain(cash.id().toString());

        // One household's chart, not two merged. Both households own an
        // "Opening Balances", so a page showing the union would print the name
        // twice — which is exactly what the original failure's output would
        // have looked like had scoping actually been broken.
        assertThat(theirs).containsOnlyOnce(DefaultAccounts.OPENING_BALANCES);
        assertThat(mine).containsOnlyOnce(DefaultAccounts.OPENING_BALANCES);
    }

    /** Hiding the control is courtesy; the annotation on the controller is the protection. */
    @Test
    void onlyAnAdminIsOfferedTheAccountManagementControls() throws Exception {
        mockMvc.perform(get("/accounts").with(UiTestAuth.as(admin)))
                .andExpect(content().string(containsString("New account")));

        mockMvc.perform(get("/accounts").with(UiTestAuth.as(plainMember)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("New account"))));
    }

    // ----------------------------------------------------------- create

    @Test
    void anAdminCanCreateAnAccount() throws Exception {
        mockMvc.perform(get("/accounts/new").with(UiTestAuth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("_csrf")));

        mockMvc.perform(post("/accounts")
                        .with(UiTestAuth.as(admin)).with(csrf())
                        .param("type", "LIABILITY")
                        .param("name", "Credit Card"))
                .andExpect(redirectedUrl("/accounts"));

        assertThat(accountService.listAccounts(admin.householdId()))
                .extracting(Account::name).contains("Credit Card");
    }

    @Test
    void aBlankNameIsRejectedOnTheFormWithTheValuesKept() throws Exception {
        mockMvc.perform(post("/accounts")
                        .with(UiTestAuth.as(admin)).with(csrf())
                        .param("type", "EXPENSE")
                        .param("name", "   "))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Enter a name")));
    }

    /** The database's UNIQUE (household_id, name) is the truth; this is the readable version of it. */
    @Test
    void aDuplicateNameIsReportedAsAConflictRatherThanACrash() throws Exception {
        mockMvc.perform(post("/accounts")
                        .with(UiTestAuth.as(admin)).with(csrf())
                        .param("type", "ASSET")
                        .param("name", DefaultAccounts.CASH))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("name is taken")));
    }

    @Test
    void aMemberWhoIsNotAnAdminIsRefusedTheWriteEndpoints() throws Exception {
        mockMvc.perform(get("/accounts/new").with(UiTestAuth.as(plainMember)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("household admins")));

        mockMvc.perform(post("/accounts")
                        .with(UiTestAuth.as(plainMember)).with(csrf())
                        .param("type", "EXPENSE")
                        .param("name", "Sneaky"))
                .andExpect(status().isForbidden());

        assertThat(accountService.listAccounts(admin.householdId()))
                .extracting(Account::name).doesNotContain("Sneaky");
    }

    @Test
    void aWriteWithoutACsrfTokenIsRefused() throws Exception {
        mockMvc.perform(post("/accounts")
                        .with(UiTestAuth.as(admin))
                        .param("type", "EXPENSE")
                        .param("name", "No Token"))
                .andExpect(status().isForbidden());

        assertThat(accountService.listAccounts(admin.householdId()))
                .extracting(Account::name).doesNotContain("No Token");
    }

    // ------------------------------------------------------------- edit

    @Test
    void anAdminCanRenameAndDeactivateAnAccount() throws Exception {
        mockMvc.perform(get("/accounts/{id}/edit", cash.id()).with(UiTestAuth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(DefaultAccounts.CASH)));

        mockMvc.perform(post("/accounts/{id}", cash.id())
                        .with(UiTestAuth.as(admin)).with(csrf())
                        .param("name", "Petty Cash")
                        .param("active", "false"))
                .andExpect(redirectedUrl("/accounts"));

        Account updated = accountService.listAccounts(admin.householdId()).stream()
                .filter(account -> account.id().equals(cash.id()))
                .findFirst().orElseThrow();

        assertThat(updated.name()).isEqualTo("Petty Cash");
        assertThat(updated.active()).isFalse();
    }

    /**
     * Saving without changing the name must not raise a duplicate-name
     * conflict against the account itself — which is what would happen if the
     * controller renamed unconditionally.
     */
    @Test
    void savingWithTheSameNameOnlyChangesTheActiveFlag() throws Exception {
        mockMvc.perform(post("/accounts/{id}", cash.id())
                        .with(UiTestAuth.as(admin)).with(csrf())
                        .param("name", DefaultAccounts.CASH)
                        .param("active", "false"))
                .andExpect(redirectedUrl("/accounts"));

        Account updated = accountService.listAccounts(admin.householdId()).stream()
                .filter(account -> account.id().equals(cash.id()))
                .findFirst().orElseThrow();

        assertThat(updated.name()).isEqualTo(DefaultAccounts.CASH);
        assertThat(updated.active()).isFalse();
    }

    // -------------------------------------------------- household scope

    /**
     * PRD §9: another household's account is 404, not 403. A 403 would confirm
     * the id exists, which is the leak that returning 404 exists to prevent.
     */
    @Test
    void anotherHouseholdsAccountIsNotFoundRatherThanForbidden() throws Exception {
        mockMvc.perform(get("/accounts/{id}/edit", otherHouseholdAccount.id())
                        .with(UiTestAuth.as(admin)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("text/html"));

        mockMvc.perform(post("/accounts/{id}", otherHouseholdAccount.id())
                        .with(UiTestAuth.as(admin)).with(csrf())
                        .param("name", "Renamed By An Outsider")
                        .param("active", "true"))
                .andExpect(status().isNotFound());

        // Not containsExactly: the other household also owns the default chart
        // seeded at creation (PRD §FR-2). What matters is that its own account
        // survived and the outsider's rename did not land.
        assertThat(accountService.listAccounts(otherHouseholdAdmin.householdId()))
                .extracting(Account::name)
                .contains("Their Safe")
                .doesNotContain("Renamed By An Outsider");
    }

    /** An id that never existed is indistinguishable from one that belongs to someone else. */
    @Test
    void anIdThatNeverExistedLooksExactlyTheSame() throws Exception {
        mockMvc.perform(get("/accounts/{id}/edit", UUID.randomUUID()).with(UiTestAuth.as(admin)))
                .andExpect(status().isNotFound());
    }

    /** PRD §9: no identifiers, SQL or stack traces in what the browser is shown. */
    @Test
    void theNotFoundPageLeaksNothingAboutWhatWasAskedFor() throws Exception {
        String body = mockMvc.perform(get("/accounts/{id}/edit", otherHouseholdAccount.id())
                        .with(UiTestAuth.as(admin)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain(otherHouseholdAccount.id().toString())
                .doesNotContain(otherHouseholdAdmin.householdId().toString())
                .doesNotContain("Their Safe")
                .doesNotContain("com.householdledger")
                .doesNotContain("select ")
                .doesNotContain("Exception");
    }
}
