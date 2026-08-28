package com.householdledger.reporting;

import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.api.SplitLine;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.reporting.api.*;
import com.householdledger.reporting.domain.DateRange;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 reporting against real PostgreSQL 16 (PRD §FR-5, §FR-6).
 *
 * <p>Reports are asserted against hand-computed figures rather than against
 * whatever the query happens to return, because a reporting bug that is
 * merely self-consistent is exactly the kind that survives testing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ReportingIT {

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

    @Autowired private ReportingService reportingService;
    @Autowired private LedgerService ledgerService;
    @Autowired private AccountService accountService;
    @Autowired private MemberProvisioningService provisioningService;

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final LocalDate TODAY = LocalDate.now();

    private Household household;
    private UUID member;
    private UUID cash;
    private UUID card;
    private UUID salary;
    private UUID groceries;
    private UUID electricity;

    @BeforeEach
    void provision() {
        household = provisioningService.createHousehold("Test Household");
        member = provisioningService.registerMember(household.id(), "Papa",
                "papa+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN).id();

        cash = accountId("Cash");
        groceries = accountId("Groceries");
        electricity = accountId("Electricity");
        card = accountService.createAccount(household.id(), AccountType.LIABILITY, "HDFC Card").id();
        salary = accountService.createAccount(household.id(), AccountType.INCOME, "Papa's Salary").id();
    }

    private UUID accountId(String name) {
        return accountService.listAccounts(household.id()).stream()
                .filter(a -> a.name().equals(name)).findFirst().orElseThrow().id();
    }

    private void record(LocalDate on, String description, UUID from, UUID to, long amount) {
        ledgerService.recordSimpleTransaction(household.id(), on, description, member, from, to, amount);
    }

    private AccountBalanceLine line(BalanceSheet sheet, UUID accountId) {
        return sheet.sections().stream()
                .flatMap(s -> s.accounts().stream())
                .filter(l -> l.accountId().equals(accountId))
                .findFirst().orElseThrow();
    }

    private BalanceSheetSection section(BalanceSheet sheet, AccountType type) {
        return sheet.sections().stream().filter(s -> s.type() == type).findFirst().orElseThrow();
    }

    // ---------- balance sheet: structure ----------

    @Test
    void theBalanceSheetHasASectionForEveryAccountTypeEvenWhenUnused() {
        // An absent section and an empty one are indistinguishable to a
        // client; the former reads like the report forgot something.
        BalanceSheet sheet = reportingService.balanceSheet(household.id(), null);

        assertThat(sheet.sections()).hasSize(AccountType.values().length);
        assertThat(sheet.sections()).extracting(BalanceSheetSection::type)
                .containsExactlyInAnyOrder(AccountType.values());
    }

    @Test
    void anAccountWithNoPostingsAppearsAtZero() {
        BalanceSheet sheet = reportingService.balanceSheet(household.id(), null);

        // "Never Used" categories are part of the chart of accounts.
        assertThat(line(sheet, electricity).balanceMinor()).isZero();
        assertThat(line(sheet, electricity).signedBalanceMinor()).isZero();
    }

    @Test
    void aDeactivatedAccountStillAppearsWithItsHistoricalBalance() {
        // PRD §FR-2: deactivated accounts "remain in historical queries".
        record(TODAY, "Weekly groceries", card, groceries, 420_000);
        accountService.setAccountActive(household.id(), groceries, false);

        BalanceSheet sheet = reportingService.balanceSheet(household.id(), null);

        assertThat(line(sheet, groceries).active()).isFalse();
        assertThat(line(sheet, groceries).balanceMinor()).isEqualTo(420_000);
    }

    @Test
    void anEmptyHouseholdGetsASuccessfulZeroBalanceSheetNotAnError() {
        BalanceSheet sheet = reportingService.balanceSheet(household.id(), null);

        assertThat(sheet.signedTotalMinor()).isZero();
        assertThat(sheet.balanced()).isTrue();
        assertThat(sheet.sections()).isNotEmpty();
    }

    // ---------- balance sheet: sign conventions (PRD §10) ----------

    @Test
    void debitNormalAccountsReadPositiveAndCreditNormalAccountsAreFlipped() {
        record(TODAY, "Salary", salary, cash, 5_000_000);
        record(TODAY, "Weekly groceries", card, groceries, 420_000);

        BalanceSheet sheet = reportingService.balanceSheet(household.id(), null);

        // Assets and expenses read as stored.
        assertThat(line(sheet, cash).balanceMinor()).isEqualTo(5_000_000);
        assertThat(line(sheet, cash).signFlipped()).isFalse();
        assertThat(line(sheet, groceries).balanceMinor()).isEqualTo(420_000);

        // A ₹4,200 card balance reads +420000 to its owner, though stored -420000.
        assertThat(line(sheet, card).signedBalanceMinor()).isEqualTo(-420_000);
        assertThat(line(sheet, card).balanceMinor()).isEqualTo(420_000);
        assertThat(line(sheet, card).signFlipped()).isTrue();

        // Income likewise.
        assertThat(line(sheet, salary).signedBalanceMinor()).isEqualTo(-5_000_000);
        assertThat(line(sheet, salary).balanceMinor()).isEqualTo(5_000_000);
    }

    @Test
    void theRawSignedTotalIsAlwaysZeroWhichMakesTheSheetSelfChecking() {
        record(TODAY, "Salary", salary, cash, 5_000_000);
        record(TODAY, "Weekly groceries", card, groceries, 420_000);
        record(TODAY, "Electricity", cash, electricity, 130_000);

        BalanceSheet sheet = reportingService.balanceSheet(household.id(), null);

        assertThat(sheet.signedTotalMinor()).isZero();
        assertThat(sheet.balanced()).isTrue();
    }

    @Test
    void sectionTotalsAggregateTheirLines() {
        record(TODAY, "Weekly groceries", card, groceries, 420_000);
        record(TODAY, "Electricity", card, electricity, 130_000);

        BalanceSheetSection expenses = section(reportingService.balanceSheet(household.id(), null),
                AccountType.EXPENSE);

        assertThat(expenses.sectionTotalMinor()).isEqualTo(550_000);
        assertThat(expenses.signedSectionTotalMinor()).isEqualTo(550_000);
    }

    @Test
    void aDebitNormalAccountMayLegitimatelyGoNegative() {
        // Spending cash you do not have overdraws it; the report says so.
        record(TODAY, "Overspend", cash, groceries, 100_000);

        assertThat(line(reportingService.balanceSheet(household.id(), null), cash).balanceMinor())
                .isEqualTo(-100_000);
    }

    // ---------- balance sheet: as-of semantics ----------

    @Test
    void asOfIncludesTransactionsOnTheBoundaryDate() {
        record(TODAY.minusDays(10), "Older", card, groceries, 100_000);
        record(TODAY.minusDays(5), "Boundary", card, groceries, 30_000);
        record(TODAY, "Later", card, groceries, 7_000);

        BalanceSheet sheet = reportingService.balanceSheet(household.id(), TODAY.minusDays(5));

        // Inclusive: the boundary day counts.
        assertThat(line(sheet, groceries).balanceMinor()).isEqualTo(130_000);
    }

    @Test
    void asOfExcludesTransactionsAfterTheCutOff() {
        record(TODAY.minusDays(10), "Older", card, groceries, 100_000);
        record(TODAY, "Later", card, groceries, 7_000);

        assertThat(line(reportingService.balanceSheet(household.id(), TODAY.minusDays(1)), groceries)
                .balanceMinor()).isEqualTo(100_000);
    }

    @Test
    void anAsOfBeforeAnyActivityShowsEveryAccountAtZeroButStillListsThem() {
        record(TODAY, "Weekly groceries", card, groceries, 420_000);

        BalanceSheet sheet = reportingService.balanceSheet(household.id(), TODAY.minusYears(5));

        assertThat(sheet.sections()).hasSize(AccountType.values().length);
        assertThat(line(sheet, groceries).balanceMinor()).isZero();
        assertThat(sheet.signedTotalMinor()).isZero();
    }

    @Test
    void aFutureAsOfIsAcceptedAndIncludesEverything() {
        // The ledger refuses far-future transactions at write time (PRD §FR-3),
        // so there is nothing beyond today for a future cut-off to exclude.
        record(TODAY, "Weekly groceries", card, groceries, 420_000);

        assertThat(line(reportingService.balanceSheet(household.id(), TODAY.plusYears(1)), groceries)
                .balanceMinor()).isEqualTo(420_000);
    }

    @Test
    void anAsOfBalanceSheetStillBalances() {
        record(TODAY.minusDays(3), "Salary", salary, cash, 5_000_000);
        record(TODAY, "Weekly groceries", card, groceries, 420_000);

        assertThat(reportingService.balanceSheet(household.id(), TODAY.minusDays(1)).balanced()).isTrue();
    }

    // ---------- balance sheet: reversal ----------

    @Test
    void reversingATransactionRestoresTheBalances() {
        var original = ledgerService.recordSimpleTransaction(household.id(), TODAY, "Mistake",
                member, card, groceries, 420_000);

        ledgerService.reverseTransaction(household.id(), original.id(), member);

        BalanceSheet sheet = reportingService.balanceSheet(household.id(), null);
        assertThat(line(sheet, groceries).balanceMinor()).isZero();
        assertThat(line(sheet, card).balanceMinor()).isZero();
        assertThat(sheet.balanced()).isTrue();
    }

    // ---------- expense summary ----------

    @Test
    void theExpenseSummaryGroupsByCategoryAndTotals() {
        record(TODAY, "Groceries one", card, groceries, 300_000);
        record(TODAY, "Groceries two", card, groceries, 120_000);
        record(TODAY, "Power", card, electricity, 130_000);

        ExpenseSummary summary = reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(1), TODAY));

        assertThat(summary.lines()).hasSize(2);
        assertThat(summary.lines()).extracting(ExpenseLine::accountName)
                .containsExactlyInAnyOrder("Groceries", "Electricity");
        assertThat(summary.lines()).filteredOn(l -> l.accountName().equals("Groceries"))
                .singleElement().extracting(ExpenseLine::totalMinor).isEqualTo(420_000L);
        assertThat(summary.totalMinor()).isEqualTo(550_000);
    }

    @Test
    void theExpenseSummaryExcludesNonExpenseAccounts() {
        record(TODAY, "Salary", salary, cash, 5_000_000);
        record(TODAY, "Groceries", card, groceries, 100_000);

        ExpenseSummary summary = reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(1), TODAY));

        assertThat(summary.lines()).extracting(ExpenseLine::accountName)
                .containsExactly("Groceries");
        assertThat(summary.totalMinor()).isEqualTo(100_000);
    }

    @Test
    void theExpenseSummaryOmitsCategoriesWithNoSpendingInTheRange() {
        record(TODAY, "Groceries", card, groceries, 100_000);

        ExpenseSummary summary = reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(1), TODAY));

        // A summary of what was spent, not a roll-call of every category.
        assertThat(summary.lines()).extracting(ExpenseLine::accountName).doesNotContain("Electricity");
    }

    @Test
    void theExpenseSummaryIsInclusiveAtBothEnds() {
        record(TODAY.minusDays(10), "Before", card, groceries, 1_000);
        record(TODAY.minusDays(5), "Start boundary", card, groceries, 20_000);
        record(TODAY.minusDays(2), "Middle", card, groceries, 300_000);
        record(TODAY, "End boundary", card, groceries, 4_000);

        ExpenseSummary summary = reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(5), TODAY));

        assertThat(summary.totalMinor()).isEqualTo(324_000);
    }

    @Test
    void aSingleDayRangeWorks() {
        record(TODAY.minusDays(1), "Yesterday", card, groceries, 50_000);
        record(TODAY, "Today", card, groceries, 70_000);

        assertThat(reportingService.expenseSummary(household.id(), DateRange.singleDay(TODAY)).totalMinor())
                .isEqualTo(70_000);
    }

    @Test
    void anEmptyExpenseSummaryIsASuccessfulAnswerNotAnError() {
        ExpenseSummary summary = reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusYears(5), TODAY.minusYears(4)));

        assertThat(summary.isEmpty()).isTrue();
        assertThat(summary.totalMinor()).isZero();
        assertThat(summary.from()).isEqualTo(TODAY.minusYears(5));
    }

    @Test
    void theExpenseSummaryIncludesDeactivatedCategoriesHistoricalSpend() {
        record(TODAY, "Spent then closed", card, groceries, 90_000);
        accountService.setAccountActive(household.id(), groceries, false);

        assertThat(reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(1), TODAY)).totalMinor()).isEqualTo(90_000);
    }

    @Test
    void aReversedTransactionNetsToZeroInTheExpenseSummary() {
        var original = ledgerService.recordSimpleTransaction(household.id(), TODAY, "Mistake",
                member, card, groceries, 420_000);
        ledgerService.reverseTransaction(household.id(), original.id(), member);

        ExpenseSummary summary = reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(1), TODAY));

        // The category is still listed — it had activity — but nets to zero.
        assertThat(summary.totalMinor()).isZero();
    }

    @Test
    void aSplitTransactionContributesToEveryCategoryItTouches() {
        ledgerService.recordSplitTransaction(household.id(), TODAY, "Combined bill", member, card,
                List.of(new SplitLine(groceries, 30_000), new SplitLine(electricity, 20_000)));

        ExpenseSummary summary = reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(1), TODAY));

        assertThat(summary.totalMinor()).isEqualTo(50_000);
        assertThat(summary.lines()).hasSize(2);
    }

    @Test
    void twoPostingsToTheSameCategoryInOneTransactionAreNotDoubleCounted() {
        // The join must not multiply rows: expect 300, not 600.
        ledgerService.recordTransaction(household.id(), TODAY, "Two legs same category", member,
                List.of(new com.householdledger.ledger.api.PostingLine(groceries, 100),
                        new com.householdledger.ledger.api.PostingLine(groceries, 200),
                        new com.householdledger.ledger.api.PostingLine(card, -300)));

        assertThat(reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(1), TODAY)).totalMinor()).isEqualTo(300);
    }

    // ---------- trial balance (PRD §FR-6) ----------

    @Test
    void theTrialBalanceIsZeroOnAHealthyLedger() {
        record(TODAY, "Salary", salary, cash, 5_000_000);
        record(TODAY, "Weekly groceries", card, groceries, 420_000);

        TrialBalance trialBalance = reportingService.trialBalance(household.id());

        assertThat(trialBalance.totalMinor()).isZero();
        assertThat(trialBalance.balanced()).isTrue();
        assertThat(trialBalance.unbalancedTransactions()).isEmpty();
    }

    @Test
    void theTrialBalanceReportsThePostingCountSoZeroIsNotAmbiguous() {
        // An empty ledger also sums to zero; only one of those is evidence.
        assertThat(reportingService.trialBalance(household.id()).isEmptyLedger()).isTrue();

        record(TODAY, "Weekly groceries", card, groceries, 420_000);

        TrialBalance trialBalance = reportingService.trialBalance(household.id());
        assertThat(trialBalance.postingCount()).isEqualTo(2);
        assertThat(trialBalance.isEmptyLedger()).isFalse();
    }

    @Test
    void theTrialBalanceIsZeroOverAtLeastTwoHundredTransactions() {
        // PRD §9 acceptance criterion, stated as a test.
        for (int i = 0; i < 200; i++) {
            record(TODAY.minusDays(i % 180), "Txn " + i, card, groceries, 1_000 + i);
        }

        TrialBalance trialBalance = reportingService.trialBalance(household.id());

        assertThat(trialBalance.postingCount()).isEqualTo(400);
        assertThat(trialBalance.totalMinor()).isZero();
        assertThat(trialBalance.balanced()).isTrue();
    }

    @Test
    void theTrialBalanceStaysZeroAcrossReversals() {
        var original = ledgerService.recordSimpleTransaction(household.id(), TODAY, "Mistake",
                member, card, groceries, 420_000);
        ledgerService.reverseTransaction(household.id(), original.id(), member);

        assertThat(reportingService.trialBalance(household.id()).balanced()).isTrue();
    }

    // ---------- household isolation (PRD §FR-1, §9) ----------

    @Test
    void anotherHouseholdsPostingsNeverAffectTheBalanceSheet() {
        record(TODAY, "Ours", card, groceries, 100_000);
        seedOtherHousehold(9_999_999);

        BalanceSheet sheet = reportingService.balanceSheet(household.id(), null);

        assertThat(line(sheet, groceries).balanceMinor()).isEqualTo(100_000);
        assertThat(sheet.sections().stream().flatMap(s -> s.accounts().stream()))
                .allMatch(l -> !l.accountName().equals("Other-only account"));
        assertThat(sheet.balanced()).isTrue();
    }

    @Test
    void anotherHouseholdsSpendingNeverAffectsTheExpenseTotal() {
        // The dangerous leak for an aggregate: a wrong *number*, with no
        // foreign row ever appearing.
        record(TODAY, "Ours", card, groceries, 100_000);
        seedOtherHousehold(9_999_999);

        assertThat(reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(1), TODAY)).totalMinor()).isEqualTo(100_000);
    }

    @Test
    void anotherHouseholdsPostingsNeverAffectTheTrialBalanceCount() {
        record(TODAY, "Ours", card, groceries, 100_000);
        seedOtherHousehold(9_999_999);

        TrialBalance trialBalance = reportingService.trialBalance(household.id());

        assertThat(trialBalance.postingCount()).isEqualTo(2);
        assertThat(trialBalance.balanced()).isTrue();
    }

    @Test
    void eachHouseholdSeesOnlyItsOwnReports() {
        record(TODAY, "Ours", card, groceries, 100_000);
        Household other = seedOtherHousehold(777_000);

        assertThat(reportingService.expenseSummary(other.id(),
                new DateRange(TODAY.minusDays(1), TODAY)).totalMinor()).isEqualTo(777_000);
        assertThat(reportingService.expenseSummary(household.id(),
                new DateRange(TODAY.minusDays(1), TODAY)).totalMinor()).isEqualTo(100_000);
    }

    private Household seedOtherHousehold(long amountMinor) {
        Household other = provisioningService.createHousehold("Other Household");
        UUID otherMember = provisioningService.registerMember(other.id(), "Them",
                "them+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN).id();
        UUID otherCash = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Cash")).findFirst().orElseThrow().id();
        UUID otherGroceries = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Groceries")).findFirst().orElseThrow().id();
        accountService.createAccount(other.id(), AccountType.ASSET, "Other-only account");

        ledgerService.recordSimpleTransaction(other.id(), TODAY, "Theirs", otherMember,
                otherCash, otherGroceries, amountMinor);
        return other;
    }
}
