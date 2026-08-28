package com.householdledger.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.householdledger.identity.api.IdentityService;
import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.domain.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The PRD §6.4 accounts endpoints over real HTTP against real PostgreSQL.
 *
 * <p>Covers the authorisation split from PRD §FR-1 (ADMIN manages accounts,
 * MEMBER reads) and the §9 requirement that another household's resources
 * return 404 rather than 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AccountApiIT {

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
    @Autowired private ObjectMapper objectMapper;

    private static final String PASSWORD = "correct-horse-battery-staple";

    private Household household;
    private String adminToken;
    private String memberToken;
    private UUID cashId;

    @BeforeEach
    void provision() {
        household = provisioningService.createHousehold("Test Household");

        String adminEmail = "admin+" + UUID.randomUUID() + "@example.com";
        provisioningService.registerMember(household.id(), "Admin", adminEmail, PASSWORD, Role.ADMIN);
        adminToken = identityService.login(adminEmail, PASSWORD).accessToken();

        String memberEmail = "member+" + UUID.randomUUID() + "@example.com";
        provisioningService.registerMember(household.id(), "Member", memberEmail, PASSWORD, Role.MEMBER);
        memberToken = identityService.login(memberEmail, PASSWORD).accessToken();

        cashId = accountId(household, "Cash");
    }

    private UUID accountId(Household h, String name) {
        return accountService.listAccounts(h.id()).stream()
                .filter(a -> a.name().equals(name)).findFirst().orElseThrow().id();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    // ---------- GET /api/accounts ----------

    @Test
    void listReturnsTheSeededAccountsForAnAdmin() throws Exception {
        mockMvc.perform(get("/api/accounts").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Cash')]").exists())
                .andExpect(jsonPath("$[?(@.name == 'Opening Balances')]").exists());
    }

    @Test
    void listIsReadableByANonAdminMember() throws Exception {
        // PRD §FR-1: MEMBER can "read everything".
        mockMvc.perform(get("/api/accounts").header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk());
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
    }

    // ---------- POST /api/accounts ----------

    @Test
    void adminCreatesAnAccount() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"LIABILITY\",\"name\":\"HDFC Card\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("HDFC Card"))
                .andExpect(jsonPath("$.type").value("LIABILITY"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void nonAdminMemberCannotCreateAnAccount() throws Exception {
        // PRD §FR-1 reserves account management to ADMIN.
        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", bearer(memberToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ASSET\",\"name\":\"Sneaky\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ASSET\",\"name\":\"Anon\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creatingADuplicateNameReturns409() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"name\":\"Groceries\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Account name already in use"));
    }

    @Test
    void creatingWithAnInvalidTypeOrBlankNameReturns400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"NOT_A_TYPE\",\"name\":\"X\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ASSET\",\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- PATCH /api/accounts/{id} ----------

    @Test
    void adminRenamesAnAccount() throws Exception {
        mockMvc.perform(patch("/api/accounts/" + cashId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cash in wallet\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cash in wallet"));
    }

    @Test
    void adminDeactivatesAnAccount() throws Exception {
        mockMvc.perform(patch("/api/accounts/" + cashId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void adminRenamesAndDeactivatesInOneRequest() throws Exception {
        mockMvc.perform(patch("/api/accounts/" + cashId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Old Cash\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Old Cash"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void nonAdminMemberCannotUpdateAnAccount() throws Exception {
        mockMvc.perform(patch("/api/accounts/" + cashId)
                        .header("Authorization", bearer(memberToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmptyPatchBodyReturns400() throws Exception {
        mockMvc.perform(patch("/api/accounts/" + cashId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchingAnUnknownAccountReturns404() throws Exception {
        mockMvc.perform(patch("/api/accounts/" + UUID.randomUUID())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());
    }

    // ---------- cross-household (PRD §9) ----------

    @Test
    void anotherHouseholdsAccountReturns404NotForbidden() throws Exception {
        Household other = provisioningService.createHousehold("Other Household");
        UUID otherCash = accountId(other, "Cash");

        // 404, deliberately: a 403 would confirm the account exists.
        mockMvc.perform(patch("/api/accounts/" + otherCash)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Stolen\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account not found"));

        mockMvc.perform(get("/api/accounts/" + otherCash + "/balance")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listNeverIncludesAnotherHouseholdsAccounts() throws Exception {
        Household other = provisioningService.createHousehold("Other Household");
        accountService.createAccount(other.id(), com.householdledger.ledger.domain.AccountType.ASSET, "Other-only");

        mockMvc.perform(get("/api/accounts").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Other-only')]").doesNotExist());
    }

    // ---------- GET /api/accounts/{id}/balance ----------

    @Test
    void balanceOfAFreshAccountIsZero() throws Exception {
        mockMvc.perform(get("/api/accounts/" + cashId + "/balance")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(cashId.toString()))
                .andExpect(jsonPath("$.accountName").value("Cash"))
                .andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void balanceIsReadableByANonAdminMember() throws Exception {
        mockMvc.perform(get("/api/accounts/" + cashId + "/balance")
                        .header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk());
    }

    @Test
    void balanceAcceptsAnAsOfDate() throws Exception {
        mockMvc.perform(get("/api/accounts/" + cashId + "/balance")
                        .param("asOf", "2026-08-28")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-08-28"));
    }

    @Test
    void balanceRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/accounts/" + cashId + "/balance"))
                .andExpect(status().isUnauthorized());
    }
}
