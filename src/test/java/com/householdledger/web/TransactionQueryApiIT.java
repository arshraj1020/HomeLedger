package com.householdledger.web;

import com.householdledger.identity.api.IdentityService;
import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.ledger.domain.PageSpec;
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
 * {@code GET /api/transactions} over real HTTP against real PostgreSQL
 * (PRD §FR-5, §6.4): query parameters, the page envelope, authorisation,
 * and household isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TransactionQueryApiIT {

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
    private UUID electricity;

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
        electricity = accountId(household, "Electricity");
        card = accountService.createAccount(household.id(), AccountType.LIABILITY, "HDFC Card").id();
    }

    private UUID accountId(Household h, String name) {
        return accountService.listAccounts(h.id()).stream()
                .filter(a -> a.name().equals(name)).findFirst().orElseThrow().id();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void record(LocalDate on, String description, UUID by, UUID to) {
        ledgerService.recordSimpleTransaction(household.id(), on, description, by, card, to, 100);
    }

    // ---------- envelope and basic listing ----------

    @Test
    void listReturnsAPageEnvelope() throws Exception {
        record(TODAY, "One", adminId, groceries);

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(PageSpec.DEFAULT_SIZE))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(false));
    }

    @Test
    void listedTransactionsCarryPostingDetail() throws Exception {
        record(TODAY, "Weekly groceries", adminId, groceries);

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].postings", hasSize(2)))
                .andExpect(jsonPath("$.content[0].postings[?(@.accountName == 'Groceries')]").exists())
                .andExpect(jsonPath("$.content[0].reversed").value(false));
    }

    @Test
    void anEmptyLedgerReturnsAnEmptyPage() throws Exception {
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized());
    }

    @Test
    void listIsReadableByANonAdminMember() throws Exception {
        // PRD §FR-1: MEMBER can "read everything".
        record(TODAY, "One", adminId, groceries);

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    // ---------- filters ----------

    @Test
    void filtersByDateRange() throws Exception {
        record(TODAY.minusDays(10), "Old", adminId, groceries);
        record(TODAY, "Recent", adminId, electricity);

        mockMvc.perform(get("/api/transactions")
                        .param("from", TODAY.minusDays(1).toString())
                        .param("to", TODAY.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].description").value("Recent"));
    }

    @Test
    void filtersByAccount() throws Exception {
        record(TODAY, "Groceries", adminId, groceries);
        record(TODAY, "Power", adminId, electricity);

        mockMvc.perform(get("/api/transactions")
                        .param("accountId", electricity.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].description").value("Power"));
    }

    @Test
    void filtersByMember() throws Exception {
        record(TODAY, "By admin", adminId, groceries);

        mockMvc.perform(get("/api/transactions")
                        .param("memberId", adminId.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        mockMvc.perform(get("/api/transactions")
                        .param("memberId", UUID.randomUUID().toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void filtersByDescriptionSubstring() throws Exception {
        record(TODAY, "Weekly Groceries", adminId, groceries);
        record(TODAY, "Electricity bill", adminId, electricity);

        mockMvc.perform(get("/api/transactions")
                        .param("q", "grocer")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].description").value("Weekly Groceries"));
    }

    @Test
    void filtersCompose() throws Exception {
        record(TODAY, "Weekly Groceries", adminId, groceries);
        record(TODAY, "Electricity bill", adminId, electricity);
        record(TODAY.minusDays(40), "Old Groceries", adminId, groceries);

        mockMvc.perform(get("/api/transactions")
                        .param("from", TODAY.minusDays(7).toString())
                        .param("accountId", groceries.toString())
                        .param("q", "grocer")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].description").value("Weekly Groceries"));
    }

    @Test
    void aReversedDateRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .param("from", TODAY.toString())
                        .param("to", TODAY.minusDays(5).toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void aMalformedDateReturns400() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .param("from", "not-a-date")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    // ---------- sorting and paging ----------

    @Test
    void resultsAreNewestFirst() throws Exception {
        record(TODAY.minusDays(2), "Oldest", adminId, groceries);
        record(TODAY, "Newest", adminId, groceries);
        record(TODAY.minusDays(1), "Middle", adminId, groceries);

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Newest"))
                .andExpect(jsonPath("$.content[1].description").value("Middle"))
                .andExpect(jsonPath("$.content[2].description").value("Oldest"));
    }

    @Test
    void honoursPageAndSize() throws Exception {
        for (int i = 0; i < 7; i++) {
            record(TODAY.minusDays(i), "Txn " + i, adminId, groceries);
        }

        mockMvc.perform(get("/api/transactions")
                        .param("page", "0").param("size", "3")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/api/transactions")
                        .param("page", "2").param("size", "3")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(true));
    }

    @Test
    void anExcessiveSizeIsClampedRatherThanRejected() throws Exception {
        record(TODAY, "One", adminId, groceries);

        mockMvc.perform(get("/api/transactions")
                        .param("size", "999999")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(PageSpec.MAX_SIZE));
    }

    @Test
    void aNegativePageIsClampedRatherThanRejected() throws Exception {
        record(TODAY, "One", adminId, groceries);

        mockMvc.perform(get("/api/transactions")
                        .param("page", "-5")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0));
    }

    // ---------- household isolation ----------

    @Test
    void listNeverIncludesAnotherHouseholdsTransactions() throws Exception {
        record(TODAY, "Ours", adminId, groceries);

        Household other = provisioningService.createHousehold("Other");
        UUID theirMember = provisioningService.registerMember(other.id(), "Them",
                "them+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN).id();
        ledgerService.recordSimpleTransaction(other.id(), TODAY, "Theirs", theirMember,
                accountId(other, "Cash"), accountId(other, "Groceries"), 100);

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].description").value("Ours"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void filteringByAnotherHouseholdsAccountReturnsAnEmptyPage() throws Exception {
        record(TODAY, "Ours", adminId, groceries);
        Household other = provisioningService.createHousehold("Other");

        // Empty rather than 404: the household predicate sits beneath the
        // filter, so there is nothing to leak and nothing to report as missing.
        mockMvc.perform(get("/api/transactions")
                        .param("accountId", accountId(other, "Cash").toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
