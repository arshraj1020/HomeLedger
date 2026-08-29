package com.householdledger.web.ui;

import com.householdledger.identity.api.IdentityService;
import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.api.PageResult;
import com.householdledger.ledger.api.TransactionDetail;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.DefaultAccounts;
import com.householdledger.ledger.domain.PageSpec;
import com.householdledger.ledger.domain.Transaction;
import com.householdledger.ledger.domain.TransactionFilter;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The transaction screens (PRD §FR-3, §FR-4, §FR-5, §FR-7).
 *
 * <p>Recording is asserted against the ledger rather than against the page:
 * what matters is that the postings written are the ones the entry mode
 * promises, not that a success message appeared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class UiTransactionsIT {

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

    private Member member;
    private Member outsider;
    private String outsiderToken;
    private UUID householdId;
    private Account cash;
    private Account groceries;
    private Account transport;
    private Account retired;

    @BeforeEach
    void provision() {
        Household household = provisioningService.createHousehold("Sharma Household");
        householdId = household.id();
        member = provisioningService.registerMember(householdId, "Papa",
                "papa+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN);

        List<Account> seeded = accountService.seedDefaultAccounts(householdId);
        cash = byName(seeded, DefaultAccounts.CASH);
        groceries = byName(seeded, "Groceries");
        transport = byName(seeded, "Transport");

        retired = accountService.createAccount(householdId,
                com.householdledger.ledger.domain.AccountType.EXPENSE, "Closed Category");
        accountService.setAccountActive(householdId, retired.id(), false);

        Household other = provisioningService.createHousehold("Other Household");
        String outsiderEmail = "neighbour+" + UUID.randomUUID() + "@example.com";
        outsider = provisioningService.registerMember(other.id(), "Neighbour",
                outsiderEmail, PASSWORD, Role.ADMIN);
        outsiderToken = identityService.login(outsiderEmail, PASSWORD).accessToken();
    }

    private static Account byName(List<Account> accounts, String name) {
        return accounts.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private Transaction record(String description, long amountMinor, Account to) {
        return ledgerService.recordSimpleTransaction(householdId, LocalDate.now(),
                description, member.id(), cash.id(), to.id(), amountMinor);
    }

    // ----------------------------------------------------- simple entry

    @Test
    void simpleEntryWritesTheTwoPostingsAndTheirSigns() throws Exception {
        mockMvc.perform(post("/transactions/new/simple")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().toString())
                        .param("description", "Weekly groceries")
                        .param("fromAccountId", cash.id().toString())
                        .param("toAccountId", groceries.id().toString())
                        .param("amount", "1,234.50"))
                .andExpect(redirectedUrlPattern("/transactions/*"));

        TransactionDetail recorded = onlyTransaction();

        assertThat(recorded.description()).isEqualTo("Weekly groceries");
        assertThat(recorded.postings()).hasSize(2);
        assertThat(recorded.postings()).anySatisfy(posting -> {
            assertThat(posting.accountName()).isEqualTo(DefaultAccounts.CASH);
            assertThat(posting.amountMinor()).isEqualTo(-123_450L);
        });
        assertThat(recorded.postings()).anySatisfy(posting -> {
            assertThat(posting.accountName()).isEqualTo("Groceries");
            assertThat(posting.amountMinor()).isEqualTo(123_450L);
        });
        assertThat(recorded.postings().stream().mapToLong(p -> p.amountMinor()).sum()).isZero();
    }

    @Test
    void aBadAmountIsAFieldErrorAndRecordsNothing() throws Exception {
        mockMvc.perform(post("/transactions/new/simple")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().toString())
                        .param("description", "Typo")
                        .param("fromAccountId", cash.id().toString())
                        .param("toAccountId", groceries.id().toString())
                        .param("amount", "12.345"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("two decimal places")))
                // The member's work is still on the page.
                .andExpect(content().string(containsString("Typo")));

        assertThat(countTransactions()).isZero();
    }

    @Test
    void theSameAccountOnBothSidesIsRefused() throws Exception {
        mockMvc.perform(post("/transactions/new/simple")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().toString())
                        .param("description", "Nowhere")
                        .param("fromAccountId", cash.id().toString())
                        .param("toAccountId", cash.id().toString())
                        .param("amount", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("two different accounts")));

        assertThat(countTransactions()).isZero();
    }

    /** PRD §FR-3: transactions record what has happened. */
    @Test
    void aFarFutureDateIsReportedOnTheFormRatherThanAsAnErrorPage() throws Exception {
        mockMvc.perform(post("/transactions/new/simple")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().plusMonths(2).toString())
                        .param("description", "Next year's rent")
                        .param("fromAccountId", cash.id().toString())
                        .param("toAccountId", groceries.id().toString())
                        .param("amount", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("in the future")));

        assertThat(countTransactions()).isZero();
    }

    /** PRD §FR-2: a deactivated account keeps its history but takes no new postings. */
    @Test
    void aDeactivatedAccountIsNotOfferedAndIsRefusedIfSubmitted() throws Exception {
        mockMvc.perform(get("/transactions/new").with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Closed Category"))));

        mockMvc.perform(post("/transactions/new/simple")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().toString())
                        .param("description", "Into a closed category")
                        .param("fromAccountId", cash.id().toString())
                        .param("toAccountId", retired.id().toString())
                        .param("amount", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("deactivated")));

        assertThat(countTransactions()).isZero();
    }

    // ------------------------------------------------------ split entry

    @Test
    void splitEntryCreditsTheSourceTheExactSumOfTheDestinations() throws Exception {
        mockMvc.perform(post("/transactions/new/split")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().toString())
                        .param("description", "Supermarket shop")
                        .param("fromAccountId", cash.id().toString())
                        .param("destinations[0].accountId", groceries.id().toString())
                        .param("destinations[0].amount", "600.00")
                        .param("destinations[1].accountId", transport.id().toString())
                        .param("destinations[1].amount", "400.00")
                        .param("destinations[2].accountId", "")
                        .param("destinations[2].amount", ""))
                .andExpect(redirectedUrlPattern("/transactions/*"));

        TransactionDetail recorded = onlyTransaction();

        assertThat(recorded.postings()).hasSize(3);
        assertThat(recorded.postings()).anySatisfy(posting ->
                assertThat(posting.amountMinor()).isEqualTo(-100_000L));
        assertThat(recorded.postings().stream().mapToLong(p -> p.amountMinor()).sum()).isZero();
    }

    @Test
    void aSplitWithNoLinesFilledInIsRefused() throws Exception {
        mockMvc.perform(post("/transactions/new/split")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().toString())
                        .param("description", "Nothing allocated")
                        .param("fromAccountId", cash.id().toString())
                        .param("destinations[0].accountId", "")
                        .param("destinations[0].amount", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("at least one line")));

        assertThat(countTransactions()).isZero();
    }

    // -------------------------------------------------------- raw entry

    @Test
    void rawEntryWritesThePostingsExactlyAsGiven() throws Exception {
        mockMvc.perform(post("/transactions/new/raw")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().toString())
                        .param("description", "Opening position")
                        .param("postings[0].accountId", cash.id().toString())
                        .param("postings[0].amount", "-250.00")
                        .param("postings[1].accountId", groceries.id().toString())
                        .param("postings[1].amount", "250.00"))
                .andExpect(redirectedUrlPattern("/transactions/*"));

        assertThat(onlyTransaction().postings().stream().mapToLong(p -> p.amountMinor()).sum()).isZero();
    }

    /**
     * The domain, the service and the database trigger all refuse an
     * unbalanced entry independently (PRD §3.2). The form reports it first so
     * the member keeps their work — it is not what enforces the rule.
     */
    @Test
    void anUnbalancedRawEntryIsRefusedAndRecordsNothing() throws Exception {
        mockMvc.perform(post("/transactions/new/raw")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().toString())
                        .param("description", "Money from nowhere")
                        .param("postings[0].accountId", cash.id().toString())
                        .param("postings[0].amount", "-250.00")
                        .param("postings[1].accountId", groceries.id().toString())
                        .param("postings[1].amount", "300.00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("cancel out")));

        assertThat(countTransactions()).isZero();
    }

    @Test
    void aSingleLineIsNotATransaction() throws Exception {
        mockMvc.perform(post("/transactions/new/raw")
                        .with(UiTestAuth.as(member)).with(csrf())
                        .param("occurredOn", LocalDate.now().toString())
                        .param("description", "Half an entry")
                        .param("postings[0].accountId", cash.id().toString())
                        .param("postings[0].amount", "-250.00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("at least two lines")));

        assertThat(countTransactions()).isZero();
    }

    // ------------------------------------------------- list and filters

    @Test
    void theListPaginatesAndKeepsTheFilterInTheLinks() throws Exception {
        for (int i = 1; i <= 30; i++) {
            record("Entry number " + i, i * 100L, groceries);
        }

        mockMvc.perform(get("/transactions").with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                // Thymeleaf escapes apostrophes in th:text output, and the total is
                // rendered inside its own span, so the assertions below match
                // apostrophe-free, element-aware fragments rather than prose.
                .andExpect(content().string(containsString(">30<")))
                .andExpect(content().string(containsString("Next")));

        mockMvc.perform(get("/transactions").param("page", "1").with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Page")));
    }

    @Test
    void theDescriptionFilterMatchesCaseInsensitivelyAndTreatsWildcardsAsText() throws Exception {
        record("Diwali sweets", 50_000L, groceries);
        record("Bus pass", 10_000L, transport);
        record("Discount 50% off", 20_000L, groceries);

        mockMvc.perform(get("/transactions").param("q", "diwali").with(UiTestAuth.as(member)))
                .andExpect(content().string(containsString("Diwali sweets")))
                .andExpect(content().string(not(containsString("Bus pass"))));

        // A literal % must not behave as a wildcard, or this would match all three.
        mockMvc.perform(get("/transactions").param("q", "%").with(UiTestAuth.as(member)))
                .andExpect(content().string(containsString("Discount 50% off")))
                .andExpect(content().string(not(containsString("Bus pass"))));
    }

    @Test
    void theAccountFilterNarrowsToPostingsAgainstThatAccount() throws Exception {
        record("Diwali sweets", 50_000L, groceries);
        record("Bus pass", 10_000L, transport);

        mockMvc.perform(get("/transactions")
                        .param("accountId", transport.id().toString())
                        .with(UiTestAuth.as(member)))
                .andExpect(content().string(containsString("Bus pass")))
                .andExpect(content().string(not(containsString("Diwali sweets"))));
    }

    /** Swapping the dates would answer a question the member did not ask. */
    @Test
    void aReversedDateRangeIsReportedRatherThanSilentlySwapped() throws Exception {
        record("Diwali sweets", 50_000L, groceries);

        mockMvc.perform(get("/transactions")
                        .param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().minusDays(7).toString())
                        .with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is after the")))
                .andExpect(content().string(not(containsString("Diwali sweets"))));
    }

    @Test
    void anEmptyListSaysSoRatherThanShowingAnEmptyTable() throws Exception {
        mockMvc.perform(get("/transactions").with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No transactions match")));
    }

    // ------------------------------------------------ detail and reverse

    @Test
    void theDetailPageShowsDebitsAndCreditsThatAgree() throws Exception {
        Transaction recorded = record("Weekly groceries", 123_450L, groceries);

        mockMvc.perform(get("/transactions/{id}", recorded.id()).with(UiTestAuth.as(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Weekly groceries")))
                .andExpect(content().string(containsString("1,234.50")))
                .andExpect(content().string(containsString("Debits equal credits")))
                .andExpect(content().string(containsString("Reverse this transaction")));
    }

    /** PRD §FR-4: reversal writes an opposite entry and leaves the original alone. */
    @Test
    void reversingWritesAnOppositeEntryAndLeavesTheOriginalUntouched() throws Exception {
        Transaction original = record("Mistaken entry", 50_000L, groceries);

        mockMvc.perform(post("/transactions/{id}/reverse", original.id())
                        .with(UiTestAuth.as(member)).with(csrf()))
                .andExpect(redirectedUrlPattern("/transactions/*"));

        TransactionDetail after = ledgerService.getTransaction(householdId, original.id());
        assertThat(after.reversed()).isTrue();
        assertThat(after.postings()).anySatisfy(posting ->
                assertThat(posting.amountMinor()).isEqualTo(-50_000L));

        assertThat(countTransactions()).isEqualTo(2L);

        mockMvc.perform(get("/transactions/{id}", original.id()).with(UiTestAuth.as(member)))
                .andExpect(content().string(containsString("has been reversed")))
                .andExpect(content().string(not(containsString("Reverse this transaction"))));
    }

    @Test
    void reversingTwiceIsAConflictNotASecondReversal() throws Exception {
        Transaction original = record("Mistaken entry", 50_000L, groceries);

        mockMvc.perform(post("/transactions/{id}/reverse", original.id())
                        .with(UiTestAuth.as(member)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/transactions/{id}/reverse", original.id())
                        .with(UiTestAuth.as(member)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("Already reversed")));

        assertThat(countTransactions()).isEqualTo(2L);
    }

    @Test
    void reversingWithoutACsrfTokenChangesNothing() throws Exception {
        Transaction original = record("Mistaken entry", 50_000L, groceries);

        mockMvc.perform(post("/transactions/{id}/reverse", original.id())
                        .with(UiTestAuth.as(member)))
                .andExpect(status().isForbidden());

        assertThat(ledgerService.getTransaction(householdId, original.id()).reversed()).isFalse();
        assertThat(countTransactions()).isEqualTo(1L);
    }

    // -------------------------------------------------- household scope

    @Test
    void anotherHouseholdsTransactionIsNotFoundRatherThanForbidden() throws Exception {
        Transaction mine = record("Private business", 50_000L, groceries);

        mockMvc.perform(get("/transactions/{id}", mine.id()).with(UiTestAuth.as(outsider)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("text/html"));

        mockMvc.perform(post("/transactions/{id}/reverse", mine.id())
                        .with(UiTestAuth.as(outsider)).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(ledgerService.getTransaction(householdId, mine.id()).reversed()).isFalse();
    }

    @Test
    void theNotFoundPageNamesNothingAboutTheTransactionAskedFor() throws Exception {
        Transaction mine = record("Private business", 50_000L, groceries);

        String body = mockMvc.perform(get("/transactions/{id}", mine.id())
                        .with(UiTestAuth.as(outsider)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain(mine.id().toString())
                .doesNotContain("Private business")
                .doesNotContain(householdId.toString())
                .doesNotContain("com.householdledger")
                .doesNotContain("Exception");
    }

    /**
     * Phase 7 must not have changed the API's error contract. The same
     * condition answers a browser with HTML and an API client with an RFC 7807
     * problem document.
     */
    @Test
    void theBrowserGetsHtmlWhileTheApiStillGetsAProblemDocument() throws Exception {
        Transaction mine = record("Private business", 50_000L, groceries);

        mockMvc.perform(get("/transactions/{id}", mine.id()).with(UiTestAuth.as(outsider)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("text/html"));

        mockMvc.perform(get("/api/transactions/{id}", mine.id())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ------------------------------------------------------------ setup

    private long countTransactions() {
        return ledgerService.findTransactions(
                householdId, TransactionFilter.UNFILTERED, new PageSpec(0, 1)).totalElements();
    }

    private TransactionDetail onlyTransaction() {
        PageResult<TransactionDetail> page = ledgerService.findTransactions(
                householdId, TransactionFilter.UNFILTERED, new PageSpec(0, 10));

        assertThat(page.totalElements()).isEqualTo(1L);
        return page.content().get(0);
    }
}
