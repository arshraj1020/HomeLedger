package com.householdledger.web.ui;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.api.PageResult;
import com.householdledger.ledger.api.TransactionDetail;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.ledger.domain.PageSpec;
import com.householdledger.ledger.domain.TransactionFilter;
import com.householdledger.reporting.api.BalanceSheet;
import com.householdledger.reporting.api.BalanceSheetSection;
import com.householdledger.reporting.api.ExpenseSummary;
import com.householdledger.reporting.api.ReportingService;
import com.householdledger.reporting.api.TrialBalance;
import com.householdledger.reporting.domain.DateRange;
import com.householdledger.web.ui.support.MoneyFormat;
import com.householdledger.web.ui.support.ViewAssembler;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * The dashboard: "account balances grouped by type" (PRD §FR-7), plus the
 * few figures that answer the questions a household actually opens this
 * application to ask.
 *
 * <p>PRD §2.3's flows are "record a transaction" and "see where the money
 * went", so the page leads with the current position, this month's spending
 * and the most recent entries, with the full chart of accounts beneath. The
 * trial balance is shown as a small badge rather than a section: PRD §FR-6
 * wants the invariant to be demonstrable, and a household that never opens
 * the reports page should still be able to see that the books balance.
 *
 * <p>Every figure comes from the reporting and ledger modules. Nothing on
 * this page is computed from another page's numbers.
 */
@Controller
class DashboardUiController {

    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    /** Enough recent entries to recognise the ledger, few enough to stay above the fold. */
    private static final int RECENT_TRANSACTIONS = 8;

    private final ReportingService reportingService;
    private final LedgerService ledgerService;
    private final ViewAssembler assembler;
    private final Clock clock;

    DashboardUiController(ReportingService reportingService, LedgerService ledgerService,
                          ViewAssembler assembler, Clock clock) {
        this.reportingService = reportingService;
        this.ledgerService = ledgerService;
        this.assembler = assembler;
        this.clock = clock;
    }

    @GetMapping("/")
    String dashboard(@AuthenticationPrincipal AuthenticatedMember member, Model model) {
        UUID householdId = member.householdId();

        BalanceSheet balanceSheet = reportingService.balanceSheet(householdId, null);

        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);
        ExpenseSummary thisMonth = reportingService.expenseSummary(
                householdId, new DateRange(monthStart, today));

        PageResult<TransactionDetail> recent = ledgerService.findTransactions(
                householdId, TransactionFilter.UNFILTERED, new PageSpec(0, RECENT_TRANSACTIONS));

        TrialBalance trialBalance = reportingService.trialBalance(householdId);

        long assets = presentationTotal(balanceSheet, AccountType.ASSET);
        long liabilities = presentationTotal(balanceSheet, AccountType.LIABILITY);
        // Both figures are already in presentation sign, so a liability reads
        // as a positive amount owed and subtracting it is the arithmetic a
        // reader expects. Doing this on the raw signed figures would add where
        // it looks like it should subtract.
        long netPosition = Math.subtractExact(assets, liabilities);

        model.addAttribute(UiModel.NAV, UiModel.NAV_DASHBOARD);
        model.addAttribute("sections", assembler.sections(balanceSheet));
        model.addAttribute("assetsTotal", MoneyFormat.format(assets));
        model.addAttribute("liabilitiesTotal", MoneyFormat.format(liabilities));
        model.addAttribute("netPosition", MoneyFormat.format(netPosition));
        model.addAttribute("netPositionNegative", netPosition < 0L);
        model.addAttribute("monthExpenses", MoneyFormat.format(thisMonth.totalMinor()));
        model.addAttribute("monthLabel", monthStart.format(MONTH_LABEL));
        model.addAttribute("monthFrom", monthStart);
        model.addAttribute("monthTo", today);
        model.addAttribute("recentTransactions", assembler.transactionRows(recent.content()));
        model.addAttribute("hasTransactions", !recent.isEmpty());
        model.addAttribute("ledgerBalanced", trialBalance.balanced());
        model.addAttribute("emptyLedger", trialBalance.isEmptyLedger());
        model.addAttribute("postingCount", trialBalance.postingCount());

        return "dashboard";
    }

    /**
     * The presentation total of one section, or zero when the household has
     * no accounts of that type.
     *
     * <p>Reads the section the reporting module already computed rather than
     * re-adding the lines: two totals for the same thing is one more than
     * there should be.
     */
    private long presentationTotal(BalanceSheet balanceSheet, AccountType type) {
        return balanceSheet.sections().stream()
                .filter(section -> section.type() == type)
                .mapToLong(BalanceSheetSection::sectionTotalMinor)
                .findFirst()
                .orElse(0L);
    }
}
