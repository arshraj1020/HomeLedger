package com.householdledger.web.ui.support;

import com.householdledger.ledger.api.PageResult;
import com.householdledger.ledger.api.PostingDetail;
import com.householdledger.ledger.api.TransactionDetail;
import com.householdledger.ledger.api.UnbalancedTransaction;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.reporting.api.AccountBalanceLine;
import com.householdledger.reporting.api.BalanceSheet;
import com.householdledger.reporting.api.BalanceSheetSection;
import com.householdledger.reporting.api.ExpenseLine;
import com.householdledger.reporting.api.ExpenseSummary;
import com.householdledger.reporting.api.TrialBalance;
import com.householdledger.web.ui.view.AccountOptionGroup;
import com.householdledger.web.ui.view.ExpenseRow;
import com.householdledger.web.ui.view.PageBar;
import com.householdledger.web.ui.view.TransactionDetailView;
import com.householdledger.web.ui.view.TransactionRow;
import com.householdledger.web.ui.view.TrialBalanceView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every figure a page shows that is not read straight out of a module API is
 * computed here, so this is where those computations are checked.
 *
 * <p>Pure unit tests: the assembler touches no database and no Spring context,
 * which is the point of keeping the arithmetic out of the templates.
 */
class ViewAssemblerTest {

    private final ViewAssembler assembler = new ViewAssembler();

    private static final UUID HOUSEHOLD = UUID.randomUUID();

    // ------------------------------------------------------- transactions

    /**
     * A transaction has no amount of its own (PRD §3.1). The headline figure
     * is the debit side; summing every posting would give zero, which is true
     * and useless.
     */
    @Test
    void headlineAmountIsTheDebitSideNotTheSumOfEverything() {
        TransactionDetail detail = transaction(
                posting("Cash", -50_000L),
                posting("Groceries", 50_000L));

        TransactionRow row = assembler.transactionRow(detail);

        assertThat(row.amount()).isEqualTo("500.00");
    }

    @Test
    void splitHeadlineIsTheWholeBillNotTheLargestLine() {
        TransactionDetail detail = transaction(
                posting("Bank", -100_000L),
                posting("Groceries", 60_000L),
                posting("Household", 40_000L));

        assertThat(assembler.transactionRow(detail).amount()).isEqualTo("1,000.00");
    }

    @Test
    void summaryNamesSourcesThenDestinations() {
        TransactionDetail detail = transaction(
                posting("Bank", -100_000L),
                posting("Groceries", 60_000L),
                posting("Household", 40_000L));

        assertThat(assembler.transactionRow(detail).summary())
                .isEqualTo("Bank → Groceries, Household");
    }

    @Test
    void postingsBecomeDebitAndCreditColumnsWithOnlyOneFilled() {
        TransactionDetailView view = assembler.detailView(transaction(
                posting("Cash", -50_000L),
                posting("Groceries", 50_000L)));

        assertThat(view.postings()).hasSize(2);
        assertThat(view.postings().get(0).credit()).isEqualTo("500.00");
        assertThat(view.postings().get(0).debit()).isEmpty();
        assertThat(view.postings().get(1).debit()).isEqualTo("500.00");
        assertThat(view.postings().get(1).credit()).isEmpty();

        assertThat(view.totalDebits()).isEqualTo("500.00");
        assertThat(view.totalCredits()).isEqualTo("500.00");
        assertThat(view.balanced()).isTrue();
    }

    /**
     * PRD §FR-4's two rules, decided once so the button matches the service:
     * a transaction is reversible exactly once, and a reversal is never
     * reversible.
     */
    @Test
    void reversibilityFollowsTheTwoRulesOfReversal() {
        assertThat(assembler.detailView(transaction(false, null,
                posting("Cash", -1L), posting("Groceries", 1L))).reversible()).isTrue();

        assertThat(assembler.detailView(transaction(true, null,
                posting("Cash", -1L), posting("Groceries", 1L))).reversible())
                .as("already reversed").isFalse();

        assertThat(assembler.detailView(transaction(false, UUID.randomUUID(),
                posting("Cash", -1L), posting("Groceries", 1L))).reversible())
                .as("is itself a reversal").isFalse();
    }

    // ------------------------------------------------------------ paging

    @Test
    void pageBarCountsFromOneForReadersWhileThePagerCountsFromZero() {
        PageBar bar = assembler.pageBar(new PageResult<>(
                List.of("a", "b", "c"), 2, 25, 137L, 6));

        assertThat(bar.page()).isEqualTo(2);
        assertThat(bar.displayPage()).isEqualTo(3);
        assertThat(bar.firstItem()).isEqualTo(51L);
        assertThat(bar.lastItem()).isEqualTo(53L);
        assertThat(bar.totalElements()).isEqualTo(137L);
        assertThat(bar.hasPrevious()).isTrue();
        assertThat(bar.hasNext()).isTrue();
        assertThat(bar.previousPage()).isEqualTo(1);
        assertThat(bar.nextPage()).isEqualTo(3);
        assertThat(bar.empty()).isFalse();
    }

    @Test
    void firstPageHasNoPreviousAndAnEmptyPageCountsNothing() {
        PageBar first = assembler.pageBar(new PageResult<>(List.of("a"), 0, 25, 1L, 1));
        assertThat(first.hasPrevious()).isFalse();
        assertThat(first.hasNext()).isFalse();
        assertThat(first.firstItem()).isEqualTo(1L);
        assertThat(first.lastItem()).isEqualTo(1L);

        PageBar none = assembler.pageBar(PageResult.empty(0, 25));
        assertThat(none.empty()).isTrue();
        assertThat(none.firstItem()).isZero();
        assertThat(none.lastItem()).isZero();
    }

    // ----------------------------------------------------- balance sheet

    @Test
    void sectionsKeepReportingsPresentationFiguresAndFlagTheFlippedOnes() {
        BalanceSheet sheet = new BalanceSheet(null, List.of(
                new BalanceSheetSection(AccountType.ASSET, List.of(
                        new AccountBalanceLine(UUID.randomUUID(), "Cash", AccountType.ASSET,
                                true, 120_000L, 120_000L, false)),
                        120_000L, 120_000L),
                new BalanceSheetSection(AccountType.LIABILITY, List.of(
                        new AccountBalanceLine(UUID.randomUUID(), "Credit Card", AccountType.LIABILITY,
                                true, 420_000L, -420_000L, true)),
                        420_000L, -420_000L)),
                0L);

        var sections = assembler.sections(sheet);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).heading()).isEqualTo("Assets");
        assertThat(sections.get(0).accounts().get(0).balance()).isEqualTo("1,200.00");
        assertThat(sections.get(0).accounts().get(0).signFlipped()).isFalse();

        assertThat(sections.get(1).heading()).isEqualTo("Liabilities");
        // Presented as an amount owed, not as a negative number — PRD §10.
        assertThat(sections.get(1).accounts().get(0).balance()).isEqualTo("4,200.00");
        assertThat(sections.get(1).accounts().get(0).signFlipped()).isTrue();
        assertThat(sections.get(1).accounts().get(0).signedBalanceMinor()).isEqualTo(-420_000L);
        assertThat(sections.get(1).total()).isEqualTo("4,200.00");
    }

    @Test
    void emptySectionsAreKeptSoAMissingTypeIsDistinguishableFromAnUnusedOne() {
        BalanceSheet sheet = new BalanceSheet(LocalDate.of(2026, 1, 31), List.of(
                new BalanceSheetSection(AccountType.EQUITY, List.of(), 0L, 0L)), 0L);

        assertThat(assembler.sections(sheet)).singleElement()
                .satisfies(section -> {
                    assertThat(section.empty()).isTrue();
                    assertThat(section.heading()).isEqualTo("Equity");
                });
    }

    // --------------------------------------------------------- expenses

    @Test
    void expenseSharesAreWholePercentagesOfThePeriodTotal() {
        ExpenseSummary summary = new ExpenseSummary(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                List.of(new ExpenseLine(UUID.randomUUID(), "Groceries", 75_000L),
                        new ExpenseLine(UUID.randomUUID(), "Transport", 25_000L)),
                100_000L);

        List<ExpenseRow> rows = assembler.expenseRows(summary);

        assertThat(rows.get(0).sharePercent()).isEqualTo(75);
        assertThat(rows.get(0).barClass()).isEqualTo("bar--75");
        assertThat(rows.get(0).total()).isEqualTo("750.00");
        assertThat(rows.get(1).sharePercent()).isEqualTo(25);
        assertThat(rows.get(1).negative()).isFalse();
    }

    /**
     * A period whose refunds cancel its spending nets to zero. Dividing by
     * that total must not blow up, and every bar simply comes out empty.
     */
    @Test
    void aPeriodThatNetsToZeroProducesEmptyBarsRatherThanAnError() {
        ExpenseSummary summary = new ExpenseSummary(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                List.of(new ExpenseLine(UUID.randomUUID(), "Groceries", 50_000L),
                        new ExpenseLine(UUID.randomUUID(), "Refunds", -50_000L)),
                0L);

        List<ExpenseRow> rows = assembler.expenseRows(summary);

        assertThat(rows).allSatisfy(row -> assertThat(row.sharePercent()).isZero());
        assertThat(rows.get(1).negative()).isTrue();
        assertThat(rows.get(1).total()).isEqualTo("-500.00");
    }

    /** Bars are CSS classes because the UI's Content Security Policy forbids inline styles. */
    @Test
    void barWidthsSnapToFivePercentSteps() {
        ExpenseSummary summary = new ExpenseSummary(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                List.of(new ExpenseLine(UUID.randomUUID(), "A", 33_00L),
                        new ExpenseLine(UUID.randomUUID(), "B", 67_00L)),
                100_00L);

        List<ExpenseRow> rows = assembler.expenseRows(summary);

        assertThat(rows).allSatisfy(row ->
                assertThat(Integer.parseInt(row.barClass().substring("bar--".length())) % 5).isZero());
    }

    // ---------------------------------------------------- trial balance

    @Test
    void trialBalanceCarriesThePostingCountBesideTheTotal() {
        TrialBalanceView healthy = assembler.trialBalanceView(new TrialBalance(0L, 42L, List.of()));

        assertThat(healthy.total()).isEqualTo("0.00");
        assertThat(healthy.postingCount()).isEqualTo(42L);
        assertThat(healthy.balanced()).isTrue();
        assertThat(healthy.emptyLedger()).isFalse();

        TrialBalanceView empty = assembler.trialBalanceView(new TrialBalance(0L, 0L, List.of()));
        assertThat(empty.emptyLedger())
                .as("zero total with no postings is not evidence of anything")
                .isTrue();
    }

    @Test
    void unbalancedTransactionsAreNamedWithHowFarOffTheyAre() {
        UUID id = UUID.randomUUID();
        TrialBalanceView view = assembler.trialBalanceView(
                new TrialBalance(-500L, 7L, List.of(new UnbalancedTransaction(id, -500L))));

        assertThat(view.balanced()).isFalse();
        assertThat(view.unbalanced()).singleElement().satisfies(row -> {
            assertThat(row.transactionId()).isEqualTo(id);
            assertThat(row.offBy()).isEqualTo("-5.00");
        });
    }

    // -------------------------------------------------- account options

    @Test
    void optionGroupsFollowAccountTypeOrderAndSkipEmptyGroups() {
        List<Account> accounts = List.of(
                account(AccountType.EXPENSE, "Transport", true),
                account(AccountType.ASSET, "Cash", true),
                account(AccountType.EXPENSE, "Groceries", true));

        List<AccountOptionGroup> groups = assembler.optionGroups(accounts, true);

        assertThat(groups).extracting(AccountOptionGroup::heading)
                .containsExactly("Assets", "Expenses");
        assertThat(groups.get(1).options()).extracting("name")
                .containsExactly("Groceries", "Transport");
    }

    /**
     * Entry forms must not offer a deactivated account: a posting against one
     * is guaranteed to be refused (PRD §FR-2). Filters still list them,
     * because retired accounts keep their history.
     */
    @Test
    void deactivatedAccountsAreOfferedForFilteringButNotForEntry() {
        List<Account> accounts = List.of(
                account(AccountType.ASSET, "Cash", true),
                account(AccountType.ASSET, "Old Wallet", false));

        assertThat(assembler.optionGroups(accounts, true).get(0).options())
                .extracting("name").containsExactly("Cash");

        assertThat(assembler.optionGroups(accounts, false).get(0).options())
                .extracting("name").containsExactly("Cash", "Old Wallet");
    }

    // ------------------------------------------------------------ setup

    private static Account account(AccountType type, String name, boolean active) {
        return new Account(UUID.randomUUID(), HOUSEHOLD, type, name, active);
    }

    private static PostingDetail posting(String accountName, long amountMinor) {
        return new PostingDetail(UUID.randomUUID(), accountName, amountMinor);
    }

    private static TransactionDetail transaction(PostingDetail... postings) {
        return transaction(false, null, postings);
    }

    private static TransactionDetail transaction(boolean reversed, UUID reverses, PostingDetail... postings) {
        return new TransactionDetail(
                UUID.randomUUID(), LocalDate.of(2026, 1, 15), "Test entry",
                UUID.randomUUID(), List.of(postings), reversed, reverses);
    }
}
