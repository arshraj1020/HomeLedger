package com.householdledger.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.householdledger.identity.api.IdentityService;
import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.domain.AccountType;
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

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code POST /api/transactions}, {@code GET /api/transactions/{id}} and
 * {@code POST /api/transactions/{id}/reverse} over real HTTP against real
 * PostgreSQL (PRD §6.4).
 *
 * <p>Includes the response-shape assertion against the literal sample in
 * PRD §6.4, and the authorisation contrast with accounts: a plain MEMBER may
 * record transactions (PRD §FR-1) even though they may not manage accounts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TransactionApiIT {

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
    private UUID card;
    private UUID groceries;
    private UUID electricity;

    @BeforeEach
    void provision() {
        household = provisioningService.createHousehold("Test Household");

        String adminEmail = "admin+" + UUID.randomUUID() + "@example.com";
        provisioningService.registerMember(household.id(), "Admin", adminEmail, PASSWORD, Role.ADMIN);
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

    private String simpleBody(UUID from, UUID to, long amount, LocalDate date) {
        return """
                {"mode":"SIMPLE","occurredOn":"%s","description":"Weekly groceries",
                 "fromAccountId":"%s","toAccountId":"%s","amountMinor":%d}
                """.formatted(date, from, to, amount);
    }

    private String recordSimpleAndReturnId() throws Exception {
        String body = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleBody(card, groceries, 420_000, LocalDate.now())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    // ---------- POST, SIMPLE ----------

    @Test
    void recordingASimpleExpenseReturns201AndThePrdResponseShape() throws Exception {
        // Mirrors the sample response in PRD §6.4: id, occurredOn,
        // description, two postings with names and signed amounts, reversed.
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleBody(card, groceries, 420_000, LocalDate.now())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.description").value("Weekly groceries"))
                .andExpect(jsonPath("$.reversed").value(false))
                .andExpect(jsonPath("$.postings", hasSize(2)))
                .andExpect(jsonPath("$.postings[?(@.accountName == 'Groceries' && @.amountMinor == 420000)]").exists())
                .andExpect(jsonPath("$.postings[?(@.accountName == 'HDFC Card' && @.amountMinor == -420000)]").exists());
    }

    @Test
    void aPlainMemberMayRecordATransaction() throws Exception {
        // PRD §FR-1: MEMBER "can record transactions" — unlike account
        // management, this is not ADMIN-only.
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(memberToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleBody(card, groceries, 100, LocalDate.now())))
                .andExpect(status().isCreated());
    }

    @Test
    void recordingRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleBody(card, groceries, 100, LocalDate.now())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void simpleModeMissingRequiredFieldsReturns400() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"SIMPLE","occurredOn":"2026-08-28","description":"Missing bits"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNonPositiveSimpleAmountReturns400() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleBody(card, groceries, 0, LocalDate.now())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aFutureDatedTransactionReturns422() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleBody(card, groceries, 100, LocalDate.now().plusDays(30))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Transaction date is too far in the future"));
    }

    @Test
    void anAccountFromAnotherHouseholdReturns404() throws Exception {
        Household other = provisioningService.createHousehold("Other");
        UUID otherGroceries = accountId(other, "Groceries");

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleBody(card, otherGroceries, 100, LocalDate.now())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account not found"));
    }

    @Test
    void aDeactivatedAccountReturns422() throws Exception {
        accountService.setAccountActive(household.id(), card, false);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleBody(card, groceries, 100, LocalDate.now())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Account is deactivated"));
    }

    // ---------- POST, SPLIT ----------

    @Test
    void recordingASplitReturns201WithAPostingPerDestinationPlusTheSource() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"SPLIT","occurredOn":"%s","description":"Combined bill",
                                 "fromAccountId":"%s",
                                 "destinations":[{"accountId":"%s","amountMinor":30000},
                                                 {"accountId":"%s","amountMinor":20000}]}
                                """.formatted(LocalDate.now(), card, groceries, electricity)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postings", hasSize(3)))
                .andExpect(jsonPath("$.postings[?(@.amountMinor == -50000)]").exists());
    }

    @Test
    void splitWithNoDestinationsReturns400() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"SPLIT","occurredOn":"%s","description":"Empty",
                                 "fromAccountId":"%s","destinations":[]}
                                """.formatted(LocalDate.now(), card)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void splitWithANegativeAllocationReturns400() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"SPLIT","occurredOn":"%s","description":"Bad",
                                 "fromAccountId":"%s",
                                 "destinations":[{"accountId":"%s","amountMinor":-100}]}
                                """.formatted(LocalDate.now(), card, groceries)))
                .andExpect(status().isBadRequest());
    }

    // ---------- POST, RAW ----------

    @Test
    void recordingARawTransactionReturns201() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAW","occurredOn":"%s","description":"Raw entry",
                                 "postings":[{"accountId":"%s","amountMinor":20000},
                                             {"accountId":"%s","amountMinor":-20000}]}
                                """.formatted(LocalDate.now(), groceries, card)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postings", hasSize(2)));
    }

    @Test
    void anUnbalancedRawTransactionReturns422WithThePrdErrorType() throws Exception {
        // PRD §6.4's sample error: 422, "Transaction is not balanced".
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAW","occurredOn":"%s","description":"Unbalanced",
                                 "postings":[{"accountId":"%s","amountMinor":20000},
                                             {"accountId":"%s","amountMinor":-19500}]}
                                """.formatted(LocalDate.now(), groceries, card)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Transaction is not balanced"))
                .andExpect(jsonPath("$.detail").value(containsString("500")));
    }

    @Test
    void aRawTransactionWithOnePostingReturns400() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAW","occurredOn":"%s","description":"Single leg",
                                 "postings":[{"accountId":"%s","amountMinor":20000}]}
                                """.formatted(LocalDate.now(), groceries)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownModeReturns400() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"TELEPORT","occurredOn":"%s","description":"?"}
                                """.formatted(LocalDate.now())))
                .andExpect(status().isBadRequest());
    }

    // ---------- GET /{id} ----------

    @Test
    void getReturnsTheTransactionWithPostingDetail() throws Exception {
        String id = recordSimpleAndReturnId();

        mockMvc.perform(get("/api/transactions/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.postings", hasSize(2)))
                .andExpect(jsonPath("$.reversed").value(false));
    }

    @Test
    void getIsReadableByANonAdminMember() throws Exception {
        String id = recordSimpleAndReturnId();

        mockMvc.perform(get("/api/transactions/" + id).header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk());
    }

    @Test
    void getRequiresAuthentication() throws Exception {
        String id = recordSimpleAndReturnId();

        mockMvc.perform(get("/api/transactions/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void getForAnUnknownTransactionReturns404() throws Exception {
        mockMvc.perform(get("/api/transactions/" + UUID.randomUUID())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    // ---------- reverse ----------

    @Test
    void reverseReturns201AndMarksTheOriginalReversed() throws Exception {
        String id = recordSimpleAndReturnId();

        mockMvc.perform(post("/api/transactions/" + id + "/reverse")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversesTransactionId").value(id))
                .andExpect(jsonPath("$.postings[?(@.amountMinor == -420000)]").exists());

        mockMvc.perform(get("/api/transactions/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(jsonPath("$.reversed").value(true));
    }

    @Test
    void reversingTwiceReturns409() throws Exception {
        String id = recordSimpleAndReturnId();
        mockMvc.perform(post("/api/transactions/" + id + "/reverse")
                .header("Authorization", bearer(adminToken))).andExpect(status().isCreated());

        mockMvc.perform(post("/api/transactions/" + id + "/reverse")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Transaction already reversed"));
    }

    @Test
    void reversingAReversalReturns409() throws Exception {
        String id = recordSimpleAndReturnId();
        String reversalBody = mockMvc.perform(post("/api/transactions/" + id + "/reverse")
                        .header("Authorization", bearer(adminToken)))
                .andReturn().getResponse().getContentAsString();
        String reversalId = objectMapper.readTree(reversalBody).get("id").asText();

        mockMvc.perform(post("/api/transactions/" + reversalId + "/reverse")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Reversals cannot be reversed"));
    }

    @Test
    void aPlainMemberMayReverseATransaction() throws Exception {
        String id = recordSimpleAndReturnId();

        mockMvc.perform(post("/api/transactions/" + id + "/reverse")
                        .header("Authorization", bearer(memberToken)))
                .andExpect(status().isCreated());
    }

    @Test
    void reverseRequiresAuthentication() throws Exception {
        String id = recordSimpleAndReturnId();

        mockMvc.perform(post("/api/transactions/" + id + "/reverse"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reversingAnUnknownTransactionReturns404() throws Exception {
        mockMvc.perform(post("/api/transactions/" + UUID.randomUUID() + "/reverse")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    // ---------- cross-household (PRD §9) ----------

    @Test
    void anotherHouseholdsTransactionIs404ForBothGetAndReverse() throws Exception {
        Household other = provisioningService.createHousehold("Other");
        String otherEmail = "other+" + UUID.randomUUID() + "@example.com";
        provisioningService.registerMember(other.id(), "Other", otherEmail, PASSWORD, Role.ADMIN);
        String otherToken = identityService.login(otherEmail, PASSWORD).accessToken();

        String theirId = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"SIMPLE","occurredOn":"%s","description":"Theirs",
                                 "fromAccountId":"%s","toAccountId":"%s","amountMinor":100}
                                """.formatted(LocalDate.now(), accountId(other, "Cash"), accountId(other, "Groceries"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(theirId).get("id").asText();

        // 404 rather than 403 — a 403 would confirm the transaction exists.
        mockMvc.perform(get("/api/transactions/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/transactions/" + id + "/reverse")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }
}
