package com.householdledger.web;

import com.householdledger.identity.api.IdentityService;
import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.domain.AccountType;
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
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three PRD §6.4 reporting endpoints over real HTTP against real
 * PostgreSQL: response shape, parameter validation, authorisation, and
 * household isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ReportApiIT {

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
    @Autowired private IdentityService identityService;
    @Autowired private AccountService accountService;
    @Autowired private LedgerService ledgerService;

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final LocalDate TODAY = LocalDate.now();

    private Household household;
    private String adminToken;
    private String memberToken;
    private UUID adminId;
    private UUID card;
    private UUID groceries;

    @BeforeEach
    void provision() {
        household = provisioningService.createHousehold("Test Household");

        String adminEmail = "admin+" + UUID.randomUUID() + "@example.com";
        adminId = provisioningService.registerMember(household.id(), "Admin", adminEmail, PASSWORD, Role.ADMIN).id();
        adminToken = identityService.login(adminEmail, PASSWORD).accessToken();

        String memberEmail = "member+" + UUID.randomUUID() + "@example.com";
        provisioningService.registerMember(household.id(), "Member", memberEmail, PASSWORD, Role.MEMBER);
        memberToken = identityService.login(memberEmail, PASSWORD).accessToken();

        groceries = accountId(household, "Groceries");
        card = accountService.createAccount(household.id(), AccountType.LIABILITY, "HDFC Card").id();
    }

    private UUID accountId(Household h, String name) {
        return accountService.listAccounts(h.id()).stream()
                .filter(a -> a.name().equals(name)).findFirst().orElseThrow().id();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void record(LocalDate on, long amount) {
        ledgerService.recordSimpleTransaction(household.id(), on, "Groceries", adminId, card, groceries, amount);
    }

    // ---------- balance sheet ----------

    @Test
    void balanceSheetReturnsSectionsForEveryAccountType() throws Exception {
        record(TODAY, 420_000);

        mockMvc.perform(get("/api/reports/balance-sheet").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections", hasSize(AccountType.values().length)))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.signedTotalMinor").value(0));
    }

    @Test
    void balanceSheetAppliesThePresentationSign() throws Exception {
        record(TODAY, 420_000);

        mockMvc.perform(get("/api/reports/balance-sheet").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                // The card reads +420000 to its owner but is stored -420000.
                .andExpect(jsonPath("$.sections[?(@.type == 'LIABILITY')].accounts[?(@.accountName == 'HDFC Card')].balanceMinor")
                        .value(org.hamcrest.Matchers.hasItem(420000)))
                .andExpect(jsonPath("$.sections[?(@.type == 'LIABILITY')].accounts[?(@.accountName == 'HDFC Card')].signedBalanceMinor")
                        .value(org.hamcrest.Matchers.hasItem(-420000)))
                .andExpect(jsonPath("$.sections[?(@.type == 'LIABILITY')].accounts[?(@.accountName == 'HDFC Card')].signFlipped")
                        .value(org.hamcrest.Matchers.hasItem(true)));
    }

    @Test
    void balanceSheetAcceptsAnAsOfDateAndEchoesItBack() throws Exception {
        record(TODAY.minusDays(10), 100_000);
        record(TODAY, 7_000);

        mockMvc.perform(get("/api/reports/balance-sheet")
                        .param("asOf", TODAY.minusDays(1).toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value(TODAY.minusDays(1).toString()))
                .andExpect(jsonPath("$.sections[?(@.type == 'EXPENSE')].sectionTotalMinor")
                        .value(org.hamcrest.Matchers.hasItem(100000)));
    }

    @Test
    void balanceSheetRejectsAMalformedAsOfDate() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet")
                        .param("asOf", "not-a-date")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void balanceSheetRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet")).andExpect(status().isUnauthorized());
    }

    @Test
    void balanceSheetIsReadableByANonAdminMember() throws Exception {
        // PRD §FR-1: MEMBER can "read everything"; §2.1's viewer persona is
        // exactly who reports are for.
        mockMvc.perform(get("/api/reports/balance-sheet").header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk());
    }

    @Test
    void anEmptyHouseholdGetsA200BalanceSheetNotA404() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true));
    }

    // ---------- expense summary ----------

    @Test
    void expenseSummaryReturnsGroupedTotals() throws Exception {
        record(TODAY, 300_000);
        record(TODAY, 120_000);

        mockMvc.perform(get("/api/reports/expenses")
                        .param("from", TODAY.minusDays(1).toString())
                        .param("to", TODAY.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].accountName").value("Groceries"))
                .andExpect(jsonPath("$.lines[0].totalMinor").value(420000))
                .andExpect(jsonPath("$.totalMinor").value(420000))
                .andExpect(jsonPath("$.from").value(TODAY.minusDays(1).toString()))
                .andExpect(jsonPath("$.to").value(TODAY.toString()));
    }

    @Test
    void expenseSummaryRequiresBothBounds() throws Exception {
        // PRD §6.4 spells the endpoint ?from=&to= — a summary without a range
        // is not a summary of anything in particular.
        mockMvc.perform(get("/api/reports/expenses")
                        .param("from", TODAY.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/reports/expenses")
                        .param("to", TODAY.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/reports/expenses").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expenseSummaryRejectsAReversedRange() throws Exception {
        mockMvc.perform(get("/api/reports/expenses")
                        .param("from", TODAY.toString())
                        .param("to", TODAY.minusDays(5).toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void expenseSummaryRejectsAMalformedDate() throws Exception {
        mockMvc.perform(get("/api/reports/expenses")
                        .param("from", "nope").param("to", TODAY.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anEmptyPeriodReturns200WithAnEmptySummary() throws Exception {
        // "Nothing was spent in this period" is an answer, not a 404.
        mockMvc.perform(get("/api/reports/expenses")
                        .param("from", TODAY.minusYears(5).toString())
                        .param("to", TODAY.minusYears(4).toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(0)))
                .andExpect(jsonPath("$.totalMinor").value(0));
    }

    @Test
    void expenseSummaryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/reports/expenses")
                        .param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expenseSummaryIsReadableByANonAdminMember() throws Exception {
        mockMvc.perform(get("/api/reports/expenses")
                        .param("from", TODAY.toString()).param("to", TODAY.toString())
                        .header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk());
    }

    // ---------- trial balance ----------

    @Test
    void trialBalanceReturnsZeroWithACount() throws Exception {
        record(TODAY, 420_000);

        mockMvc.perform(get("/api/reports/trial-balance").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinor").value(0))
                .andExpect(jsonPath("$.postingCount").value(2))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.emptyLedger").value(false))
                .andExpect(jsonPath("$.unbalancedTransactions", hasSize(0)));
    }

    @Test
    void trialBalanceDistinguishesAnEmptyLedgerFromABalancedOne() throws Exception {
        mockMvc.perform(get("/api/reports/trial-balance").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinor").value(0))
                .andExpect(jsonPath("$.postingCount").value(0))
                .andExpect(jsonPath("$.emptyLedger").value(true));
    }

    @Test
    void trialBalanceRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/reports/trial-balance")).andExpect(status().isUnauthorized());
    }

    @Test
    void trialBalanceIsReadableByANonAdminMember() throws Exception {
        mockMvc.perform(get("/api/reports/trial-balance").header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk());
    }

    // ---------- household isolation ----------

    @Test
    void reportsNeverIncludeAnotherHouseholdsFigures() throws Exception {
        record(TODAY, 100_000);

        Household other = provisioningService.createHousehold("Other Household");
        UUID otherMember = provisioningService.registerMember(other.id(), "Them",
                "them+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN).id();
        ledgerService.recordSimpleTransaction(other.id(), TODAY, "Theirs", otherMember,
                accountId(other, "Cash"), accountId(other, "Groceries"), 9_999_999);

        // The aggregate leak that matters: a wrong number, with no foreign
        // row ever appearing in the response.
        mockMvc.perform(get("/api/reports/expenses")
                        .param("from", TODAY.minusDays(1).toString()).param("to", TODAY.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinor").value(100000));

        mockMvc.perform(get("/api/reports/trial-balance").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postingCount").value(2));
    }

    @Test
    void thereIsNoWayToAskForAnotherHouseholdsReport() throws Exception {
        // No endpoint accepts a householdId, so a client cannot even express
        // the request; a stray parameter is ignored rather than honoured.
        Household other = provisioningService.createHousehold("Other Household");
        record(TODAY, 100_000);

        mockMvc.perform(get("/api/reports/trial-balance")
                        .param("householdId", other.id().toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postingCount").value(2));
    }
}
