package com.householdledger.ledger;

import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.api.PageResult;
import com.householdledger.ledger.api.TransactionDetail;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.ledger.domain.PageSpec;
import com.householdledger.ledger.domain.TransactionFilter;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §FR-5 querying, against real PostgreSQL 16: the dynamically composed
 * Specifications, pagination, and the fixed date-descending sort.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TransactionQueryIT {

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

    @Autowired private LedgerService ledgerService;
    @Autowired private AccountService accountService;
    @Autowired private MemberProvisioningService provisioningService;

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final LocalDate TODAY = LocalDate.now();

    private Household household;
    private UUID papa;
    private UUID mummy;
    private UUID card;
    private UUID cash;
    private UUID groceries;
    private UUID electricity;

    @BeforeEach
    void provision() {
        household = provisioningService.createHousehold("Test Household");
        papa = provisioningService.registerMember(household.id(), "Papa",
                "papa+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN).id();
        mummy = provisioningService.registerMember(household.id(), "Mummy",
                "mummy+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.MEMBER).id();

        cash = accountId("Cash");
        groceries = accountId("Groceries");
        electricity = accountId("Electricity");
        card = accountService.createAccount(household.id(), AccountType.LIABILITY, "HDFC Card").id();
    }

    private UUID accountId(String name) {
        return accountService.listAccounts(household.id()).stream()
                .filter(a -> a.name().equals(name)).findFirst().orElseThrow().id();
    }

    private UUID record(LocalDate on, String description, UUID by, UUID from, UUID to, long amount) {
        return ledgerService.recordSimpleTransaction(household.id(), on, description, by, from, to, amount).id();
    }

    private PageResult<TransactionDetail> query(TransactionFilter filter) {
        return ledgerService.findTransactions(household.id(), filter, new PageSpec(0, PageSpec.MAX_SIZE));
    }

    private List<String> descriptionsOf(PageResult<TransactionDetail> page) {
        return page.content().stream().map(TransactionDetail::description).toList();
    }

    // ---------- unfiltered ----------

    @Test
    void listsEveryTransactionInTheHouseholdWhenUnfiltered() {
        record(TODAY, "One", papa, card, groceries, 100);
        record(TODAY, "Two", papa, card, electricity, 200);

        PageResult<TransactionDetail> page = query(TransactionFilter.UNFILTERED);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(descriptionsOf(page)).containsExactlyInAnyOrder("One", "Two");
    }

    @Test
    void anEmptyLedgerReturnsAnEmptyPageNotAnError() {
        PageResult<TransactionDetail> page = query(TransactionFilter.UNFILTERED);

        assertThat(page.isEmpty()).isTrue();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void listedTransactionsCarryFullPostingDetailWithAccountNames() {
        record(TODAY, "Weekly groceries", papa, card, groceries, 420_000);

        TransactionDetail detail = query(TransactionFilter.UNFILTERED).content().get(0);

        assertThat(detail.postings()).hasSize(2);
        assertThat(detail.postings()).extracting(p -> p.accountName())
                .containsExactlyInAnyOrder("Groceries", "HDFC Card");
        assertThat(detail.reversed()).isFalse();
    }

    @Test
    void aReversedTransactionIsFlaggedInListings() {
        UUID original = record(TODAY, "Mistake", papa, card, groceries, 100);
        ledgerService.reverseTransaction(household.id(), original, papa);

        PageResult<TransactionDetail> page = query(TransactionFilter.UNFILTERED);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).filteredOn(d -> d.id().equals(original))
                .singleElement().extracting(TransactionDetail::reversed).isEqualTo(true);
        assertThat(page.content()).filteredOn(TransactionDetail::isReversal).hasSize(1);
    }

    // ---------- date range ----------

    @Test
    void filtersByDateRangeInclusivelyAtBothEnds() {
        record(TODAY.minusDays(10), "Old", papa, card, groceries, 100);
        record(TODAY.minusDays(5), "Middle", papa, card, groceries, 100);
        record(TODAY, "Recent", papa, card, groceries, 100);

        PageResult<TransactionDetail> page = query(new TransactionFilter(
                TODAY.minusDays(10), TODAY.minusDays(5), null, null, null));

        assertThat(descriptionsOf(page)).containsExactlyInAnyOrder("Old", "Middle");
    }

    @Test
    void filtersByAnOpenEndedLowerBound() {
        record(TODAY.minusDays(10), "Old", papa, card, groceries, 100);
        record(TODAY, "Recent", papa, card, groceries, 100);

        PageResult<TransactionDetail> page = query(new TransactionFilter(
                TODAY.minusDays(1), null, null, null, null));

        assertThat(descriptionsOf(page)).containsExactly("Recent");
    }

    @Test
    void filtersByAnOpenEndedUpperBound() {
        record(TODAY.minusDays(10), "Old", papa, card, groceries, 100);
        record(TODAY, "Recent", papa, card, groceries, 100);

        PageResult<TransactionDetail> page = query(new TransactionFilter(
                null, TODAY.minusDays(1), null, null, null));

        assertThat(descriptionsOf(page)).containsExactly("Old");
    }

    @Test
    void aRangeMatchingNothingReturnsAnEmptyPage() {
        record(TODAY, "Recent", papa, card, groceries, 100);

        PageResult<TransactionDetail> page = query(new TransactionFilter(
                TODAY.minusYears(5), TODAY.minusYears(4), null, null, null));

        assertThat(page.isEmpty()).isTrue();
    }

    // ---------- account ----------

    @Test
    void filtersByAccountOnEitherSideOfTheTransaction() {
        record(TODAY, "Groceries on card", papa, card, groceries, 100);
        record(TODAY, "Electricity on card", papa, card, electricity, 100);

        // The account filter matches a posting on either leg, so the funding
        // account finds both and the category account finds only its own.
        assertThat(descriptionsOf(query(new TransactionFilter(null, null, card, null, null))))
                .containsExactlyInAnyOrder("Groceries on card", "Electricity on card");
        assertThat(descriptionsOf(query(new TransactionFilter(null, null, groceries, null, null))))
                .containsExactly("Groceries on card");
    }

    @Test
    void theAccountFilterFindsSplitLegs() {
        ledgerService.recordSplitTransaction(household.id(), TODAY, "Combined bill", papa, card,
                List.of(new com.householdledger.ledger.api.SplitLine(groceries, 30_000),
                        new com.householdledger.ledger.api.SplitLine(electricity, 20_000)));

        assertThat(descriptionsOf(query(new TransactionFilter(null, null, electricity, null, null))))
                .containsExactly("Combined bill");
    }

    @Test
    void theAccountFilterDoesNotDuplicateATransactionWithSeveralMatchingPostings() {
        // An EXISTS subquery rather than a join is what prevents this: a join
        // would return the transaction once per matching posting.
        ledgerService.recordTransaction(household.id(), TODAY, "Two legs same account", papa,
                List.of(new com.householdledger.ledger.api.PostingLine(groceries, 100),
                        new com.householdledger.ledger.api.PostingLine(groceries, 200),
                        new com.householdledger.ledger.api.PostingLine(card, -300)));

        PageResult<TransactionDetail> page = query(new TransactionFilter(null, null, groceries, null, null));

        assertThat(page.content()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void anAccountFromAnotherHouseholdMatchesNothingRatherThanErroring() {
        record(TODAY, "Ours", papa, card, groceries, 100);
        Household other = provisioningService.createHousehold("Other");
        UUID theirCash = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Cash")).findFirst().orElseThrow().id();

        assertThat(query(new TransactionFilter(null, null, theirCash, null, null)).isEmpty()).isTrue();
    }

    // ---------- member ----------

    @Test
    void filtersByRecordingMember() {
        record(TODAY, "By Papa", papa, card, groceries, 100);
        record(TODAY, "By Mummy", mummy, card, groceries, 100);

        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, papa, null))))
                .containsExactly("By Papa");
        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, mummy, null))))
                .containsExactly("By Mummy");
    }

    // ---------- free-text ----------

    @Test
    void filtersByDescriptionSubstringCaseInsensitively() {
        record(TODAY, "Weekly Groceries", papa, card, groceries, 100);
        record(TODAY, "Electricity bill", papa, card, electricity, 100);

        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "grocer"))))
                .containsExactly("Weekly Groceries");
        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "GROCER"))))
                .containsExactly("Weekly Groceries");
    }

    @Test
    void aBlankSearchTermBehavesAsNoFilterRatherThanMatchingNothing() {
        record(TODAY, "One", papa, card, groceries, 100);
        record(TODAY, "Two", papa, card, electricity, 100);

        assertThat(query(new TransactionFilter(null, null, null, null, "   ")).totalElements()).isEqualTo(2);
    }

    /**
     * A literal {@code %} in the search term matches a literal {@code %} in
     * the description — and, critically, <b>only</b> those descriptions.
     *
     * <p>The discriminating assertion is the last one. Searching for
     * {@code "%"} must return exactly the one row that contains a percent
     * sign, not all four: an unescaped {@code %} becomes the LIKE
     * "any sequence" wildcard, so the pattern {@code %%%} matches every row
     * in the table. Asserting merely that the result is non-empty, or that
     * it contains the percent row, would pass under both the correct and the
     * broken implementation — the count is what separates them.
     */
    @Test
    void aLiteralPercentInTheSearchTermMatchesOnlyDescriptionsContainingOne() {
        record(TODAY, "Discount 50% off", papa, card, groceries, 100);
        record(TODAY, "Ordinary shopping", papa, card, electricity, 100);
        record(TODAY, "Another 50 rupees", papa, card, cash, 100);

        // Term containing a percent: matches the literal text "50%", so the
        // row saying plain "50 rupees" is excluded.
        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "50%"))))
                .containsExactly("Discount 50% off");

        // Bare percent: a wildcard would match all three rows.
        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "%"))))
                .containsExactly("Discount 50% off");
    }

    /**
     * The same guarantee for {@code _}, which in LIKE matches exactly one
     * character. "reference" is the trap: {@code %ref_%} unescaped matches it
     * (the {@code _} standing in for "e"), so a passing result here means the
     * underscore was treated as literal text.
     */
    @Test
    void aLiteralUnderscoreInTheSearchTermMatchesOnlyDescriptionsContainingOne() {
        record(TODAY, "ref_1234", papa, card, groceries, 100);
        record(TODAY, "reference", papa, card, electricity, 100);
        record(TODAY, "refX9999", papa, card, cash, 100);

        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "ref_"))))
                .containsExactly("ref_1234");

        // A bare underscore matches any single character when unescaped, so
        // this would return all three rows if escaping were broken.
        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "_"))))
                .containsExactly("ref_1234");
    }

    /**
     * A backslash is the escape character itself, so it needs escaping before
     * the wildcards are — otherwise the escaping of {@code %} and {@code _}
     * can be subverted by a term that already contains one.
     */
    @Test
    void aLiteralBackslashInTheSearchTermIsMatchedAsText() {
        record(TODAY, "path C:\\Users", papa, card, groceries, 100);
        record(TODAY, "no slashes here", papa, card, electricity, 100);

        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "\\"))))
                .containsExactly("path C:\\Users");
    }

    @Test
    void escapingDoesNotDisturbOrdinarySubstringSearch() {
        // Regression guard: the escaping must not alter behaviour for the
        // overwhelmingly common case of a term with no metacharacters at all.
        record(TODAY, "Weekly groceries", papa, card, groceries, 100);
        record(TODAY, "Electricity bill", papa, card, electricity, 100);

        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "grocer"))))
                .containsExactly("Weekly groceries");
        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "bill"))))
                .containsExactly("Electricity bill");
        assertThat(query(new TransactionFilter(null, null, null, null, "nothing matches this"))
                .isEmpty()).isTrue();
    }

    @Test
    void escapingPreservesCaseInsensitiveMatchingOnTermsContainingMetacharacters() {
        // Both halves at once: case-folding still applies to a term that also
        // needs escaping.
        record(TODAY, "Discount 50% OFF Groceries", papa, card, groceries, 100);
        record(TODAY, "Ordinary shopping", papa, card, electricity, 100);

        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "50% off"))))
                .containsExactly("Discount 50% OFF Groceries");
        assertThat(descriptionsOf(query(new TransactionFilter(null, null, null, null, "50% OFF"))))
                .containsExactly("Discount 50% OFF Groceries");
    }

    // ---------- composition ----------

    @Test
    void criteriaCompose() {
        record(TODAY, "Papa groceries", papa, card, groceries, 100);
        record(TODAY, "Mummy groceries", mummy, card, groceries, 100);
        record(TODAY.minusDays(30), "Papa old groceries", papa, card, groceries, 100);
        record(TODAY, "Papa electricity", papa, card, electricity, 100);

        PageResult<TransactionDetail> page = query(new TransactionFilter(
                TODAY.minusDays(1), TODAY, groceries, papa, "grocer"));

        assertThat(descriptionsOf(page)).containsExactly("Papa groceries");
    }

    // ---------- sorting and pagination ----------

    @Test
    void resultsAreSortedByDateDescending() {
        record(TODAY.minusDays(2), "Oldest", papa, card, groceries, 100);
        record(TODAY, "Newest", papa, card, groceries, 100);
        record(TODAY.minusDays(1), "Middle", papa, card, groceries, 100);

        assertThat(descriptionsOf(query(TransactionFilter.UNFILTERED)))
                .containsExactly("Newest", "Middle", "Oldest");
    }

    @Test
    void paginationReportsTotalsAndNavigationFlags() {
        for (int i = 0; i < 7; i++) {
            record(TODAY.minusDays(i), "Txn " + i, papa, card, groceries, 100);
        }

        PageResult<TransactionDetail> first = ledgerService.findTransactions(
                household.id(), TransactionFilter.UNFILTERED, new PageSpec(0, 3));

        assertThat(first.content()).hasSize(3);
        assertThat(first.totalElements()).isEqualTo(7);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.hasPrevious()).isFalse();

        PageResult<TransactionDetail> last = ledgerService.findTransactions(
                household.id(), TransactionFilter.UNFILTERED, new PageSpec(2, 3));

        assertThat(last.content()).hasSize(1);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.hasPrevious()).isTrue();
    }

    @Test
    void pagingPartitionsTheResultSetWithNoDuplicatesOrOmissions() {
        // The reason the sort carries tiebreakers: every transaction here
        // shares one date, so without a total order Postgres could return a
        // row on two pages, or on none.
        int total = 20;
        for (int i = 0; i < total; i++) {
            record(TODAY, "Same day " + i, papa, card, groceries, 100 + i);
        }

        List<UUID> seen = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            ledgerService.findTransactions(household.id(), TransactionFilter.UNFILTERED, new PageSpec(page, 5))
                    .content().forEach(d -> seen.add(d.id()));
        }

        Set<UUID> unique = new HashSet<>(seen);
        assertThat(seen).hasSize(total);
        assertThat(unique).hasSize(total);
    }

    @Test
    void aPageBeyondTheEndIsEmptyButStillReportsTotals() {
        record(TODAY, "Only one", papa, card, groceries, 100);

        PageResult<TransactionDetail> page = ledgerService.findTransactions(
                household.id(), TransactionFilter.UNFILTERED, new PageSpec(50, 10));

        assertThat(page.isEmpty()).isTrue();
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void anExcessivePageSizeIsClampedRatherThanHonoured() {
        record(TODAY, "One", papa, card, groceries, 100);

        PageResult<TransactionDetail> page = ledgerService.findTransactions(
                household.id(), TransactionFilter.UNFILTERED, new PageSpec(0, 10_000));

        assertThat(page.size()).isEqualTo(PageSpec.MAX_SIZE);
    }

    // ---------- household isolation (PRD §FR-1, §9) ----------

    @Test
    void listingNeverIncludesAnotherHouseholdsTransactions() {
        record(TODAY, "Ours", papa, card, groceries, 100);

        Household other = provisioningService.createHousehold("Other");
        UUID theirMember = provisioningService.registerMember(other.id(), "Them",
                "them+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN).id();
        UUID theirCash = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Cash")).findFirst().orElseThrow().id();
        UUID theirGroceries = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Groceries")).findFirst().orElseThrow().id();
        ledgerService.recordSimpleTransaction(other.id(), TODAY, "Theirs", theirMember,
                theirCash, theirGroceries, 100);

        assertThat(descriptionsOf(query(TransactionFilter.UNFILTERED))).containsExactly("Ours");
        assertThat(ledgerService.findTransactions(other.id(), TransactionFilter.UNFILTERED, PageSpec.first())
                .content()).extracting(TransactionDetail::description).containsExactly("Theirs");
    }

    @Test
    void aMemberIdFromAnotherHouseholdMatchesNothing() {
        record(TODAY, "Ours", papa, card, groceries, 100);
        Household other = provisioningService.createHousehold("Other");
        UUID theirMember = provisioningService.registerMember(other.id(), "Them",
                "them+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN).id();

        assertThat(query(new TransactionFilter(null, null, null, theirMember, null)).isEmpty()).isTrue();
    }
}
