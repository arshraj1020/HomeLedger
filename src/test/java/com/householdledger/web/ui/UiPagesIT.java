package com.householdledger.web.ui;

import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.api.PostingLine;
import com.householdledger.ledger.domain.Account;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard and the three report pages (PRD §FR-5, §FR-6, §FR-7).
 *
 * <p>The figures are asserted exactly, because the point of these pages is the
 * numbers. A test that only checked the pages rendered would pass with every
 * balance shown as zero.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class UiPagesIT {

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
    @Autowired private LedgerService ledgerService;

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** ₹10,000 opening cash, in paise. */
    private static final long OPENING_CASH = 10_000_00L;

    /** ₹1,234.50 of groceries, in paise. */
    private static final long GROCERIES_SPEND = 1_234_50L;

    private Member member;
    private Member freshMember;
    private UUID householdId;
    private UUID openingTransactionId;
    private UUID groceriesTransactionId;

    @BeforeEach
    void provision() {
        Household household = provisioningService.createHousehold("Sharma Household");
        householdId = household.id();
        member = provisioningService.registerMember(householdId, "Papa",
                "papa+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN);

        List<Account> seeded = accountService.seedDefaultAccounts(householdId);
        Account cash = byName(seeded, DefaultAccounts.CASH);
        Account opening = byName(seeded, DefaultAccounts.OPENING_BALANCES);
        Account groceries = byName(seeded, "Groceries");

        // Opening position: cash debited, equity credited. Raw mode, because
        // this is exactly the entry PRD §FR-3 keeps raw mode around for.
        openingTransactionId = ledgerService.recordTransaction(householdId, LocalDate.now(), "Opening balance",
                member.id(), List.of(new PostingLine(cash.id(), OPENING_CASH),
                        new PostingLine(opening.id(), -OPENING_CASH))).id();

        groceriesTransactionId = ledgerService.recordSimpleTransaction(householdId, LocalDate.now(),
                "Weekly groceries", member.id(), cash.id(), groceries.id(), GROCERIES_SPEND).id();

        Household empty = provisioningService.createHousehold("Empty Household");
        freshMember = provisioningService.registerMember(empty.id(), "Nobody",
                "nobody+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.MEMBER);
    }

    private static Account byName(List<Account> accounts, String name) {
        return accounts.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    // -------------------------------------------------------- dashboard

    @Test
    void theDashboardShowsThePositionTheSpendingAndTheRecentEntries() throws Exception {
        mockMvc.perform(get("/").with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                // 10,000.00 opening less 1,234.50 spent.
                .andExpect(content().string(containsString("8,765.50")))
                .andExpect(content().string(containsString("1,234.50")))
                .andExpect(content().string(containsString("Weekly groceries")))
                .andExpect(content().string(containsString("Opening balance")))
                .andExpect(content().string(containsString("Books balance")));
    }

    /**
     * Equity is credit-normal, so its stored balance is negative and its
     * presentation figure is positive (PRD §10). The dashboard must show the
     * figure a person expects, not the stored one.
     */
    @Test
    void creditNormalBalancesAreShownTheWayAStatementReadsThem() throws Exception {
        String body = mockMvc.perform(get("/").with(UiTestAuth.as(member)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("10,000.00");
        assertThat(body).doesNotContain("-10,000.00");
    }

    @Test
    void anEmptyHouseholdGetsAnInvitationRatherThanAWallOfZeroes() throws Exception {
        mockMvc.perform(get("/").with(UiTestAuth.as(freshMember)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing recorded yet")))
                .andExpect(content().string(containsString("No postings yet")))
                .andExpect(content().string(not(containsString("Weekly groceries"))));
    }

    /**
     * Isolation asserted on transaction ids rather than on descriptions.
     *
     * <p>The ids are what the dashboard's links are built from, they are
     * unique, and they cannot collide with anything else on the page. A
     * description can: this test previously looked for the absence of
     * "Opening balance", which differs from the seeded account name
     * "Opening Balances" only by a lower-case b and a plural. It passed, but it
     * passed on the case of one letter — and every household is seeded with
     * that account (PRD §FR-2), so the very next household would have rendered
     * a near-identical string.
     *
     * <p>Both directions are asserted, so neither half can pass because ids
     * are absent from the markup altogether.
     */
    @Test
    void theDashboardNeverShowsAnotherHouseholdsEntries() throws Exception {
        String owner = mockMvc.perform(get("/").with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String stranger = mockMvc.perform(get("/").with(UiTestAuth.as(freshMember)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(owner)
                .contains(openingTransactionId.toString())
                .contains(groceriesTransactionId.toString());

        assertThat(stranger)
                .doesNotContain(openingTransactionId.toString())
                .doesNotContain(groceriesTransactionId.toString())
                .doesNotContain("Weekly groceries")
                .doesNotContain("8,765.50");
    }

    // ---------------------------------------------------- balance sheet

    @Test
    void theBalanceSheetGroupsAccountsAndProvesItsOwnSignedTotalIsZero() throws Exception {
        mockMvc.perform(get("/reports/balance-sheet").with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Assets")))
                .andExpect(content().string(containsString("Equity")))
                .andExpect(content().string(containsString("Expenses")))
                .andExpect(content().string(containsString(DefaultAccounts.CASH)))
                .andExpect(content().string(containsString("8,765.50")))
                .andExpect(content().string(containsString("Signed total is zero")));
    }

    /**
     * PRD §FR-2 keeps accounts with no postings in the chart, so the balance
     * sheet still lists them at zero rather than dropping them.
     */
    @Test
    void accountsWithNoPostingsStillAppearAtZero() throws Exception {
        mockMvc.perform(get("/reports/balance-sheet").with(UiTestAuth.as(member)))
                .andExpect(content().string(containsString("Rent")))
                .andExpect(content().string(containsString("School Fees")));
    }

    /** An as-of date before the ledger begins shows a household that has not started yet. */
    @Test
    void theAsOfDateBoundsWhatIsIncluded() throws Exception {
        mockMvc.perform(get("/reports/balance-sheet")
                        .param("asOf", LocalDate.now().minusYears(1).toString())
                        .with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(DefaultAccounts.CASH)))
                .andExpect(content().string(not(containsString("8,765.50"))));
    }

    // --------------------------------------------------------- expenses

    @Test
    void theExpenseSummaryDefaultsToThisMonthAndTotalsTheCategories() throws Exception {
        mockMvc.perform(get("/reports/expenses").with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Groceries")))
                .andExpect(content().string(containsString("1,234.50")));
    }

    @Test
    void aPeriodWithNoSpendingIsASuccessfulEmptyReport() throws Exception {
        mockMvc.perform(get("/reports/expenses")
                        .param("from", LocalDate.now().minusYears(2).toString())
                        .param("to", LocalDate.now().minusYears(2).plusDays(3).toString())
                        .with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing was spent in this period")));
    }

    /**
     * {@code DateRange} refuses a reversed range rather than swapping it, and
     * the page says so rather than showing an error page.
     */
    @Test
    void aReversedRangeIsExplainedOnThePage() throws Exception {
        mockMvc.perform(get("/reports/expenses")
                        .param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().minusDays(30).toString())
                        .with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is after the")));
    }

    @Test
    void expensesAreScopedToTheCallersHousehold() throws Exception {
        mockMvc.perform(get("/reports/expenses").with(UiTestAuth.as(freshMember)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("1,234.50"))));
    }

    // ---------------------------------------------------- trial balance

    /** PRD §FR-6: "this exists to be demonstrable". */
    @Test
    void theTrialBalanceShowsBothTheTotalAndHowManyPostingsMadeIt() throws Exception {
        mockMvc.perform(get("/reports/trial-balance").with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("0.00")))
                .andExpect(content().string(containsString("Balanced")))
                // Four postings: two for the opening entry, two for the groceries.
                .andExpect(content().string(containsString(">4<")))
                .andExpect(content().string(not(containsString("Out of balance"))));
    }

    /**
     * A zero total with no postings is not evidence of anything, so the page
     * says which it is.
     */
    @Test
    void anEmptyLedgerIsLabelledRatherThanReportedAsBalanced() throws Exception {
        mockMvc.perform(get("/reports/trial-balance").with(UiTestAuth.as(freshMember)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No postings yet")))
                .andExpect(content().string(not(containsString("Out of balance"))));
    }

    @Test
    void everyReportPageIsReachableFromTheReportsTabs() throws Exception {
        for (String path : new String[]{"/reports/balance-sheet", "/reports/expenses", "/reports/trial-balance"}) {
            mockMvc.perform(get(path).with(UiTestAuth.as(member)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Balance sheet")))
                    .andExpect(content().string(containsString("Trial balance")));
        }
    }
}
