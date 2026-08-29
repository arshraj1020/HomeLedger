package com.householdledger.web.ui;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.reporting.api.BalanceSheet;
import com.householdledger.reporting.api.ExpenseSummary;
import com.householdledger.reporting.api.ReportingService;
import com.householdledger.reporting.api.TrialBalance;
import com.householdledger.reporting.domain.DateRange;
import com.householdledger.web.ui.support.MoneyFormat;
import com.householdledger.web.ui.support.ViewAssembler;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The three reports of PRD §FR-5 and §FR-6, rendered as pages.
 *
 * <p>Every number here comes from {@code ReportingService}. The UI does not
 * total, group, or sign anything itself — PRD §10 puts the sign convention in
 * the reporting layer and nowhere else, and a page that re-derived a total
 * would be a second answer to a question that already has one.
 *
 * <p><b>Reports never 404.</b> A household with no accounts, or no spending
 * in the period, gets a successful empty report. "Nothing here" is an answer,
 * and a 404 would additionally be a wrong one, implying the household itself
 * was missing.
 */
@Controller
@RequestMapping("/reports")
class ReportUiController {

    private final ReportingService reportingService;
    private final ViewAssembler assembler;
    private final Clock clock;

    ReportUiController(ReportingService reportingService, ViewAssembler assembler, Clock clock) {
        this.reportingService = reportingService;
        this.assembler = assembler;
        this.clock = clock;
    }

    @GetMapping("/balance-sheet")
    String balanceSheet(@AuthenticationPrincipal AuthenticatedMember member,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                        Model model) {

        BalanceSheet sheet = reportingService.balanceSheet(member.householdId(), asOf);

        model.addAttribute(UiModel.NAV, UiModel.NAV_REPORTS);
        model.addAttribute("report", "balance-sheet");
        model.addAttribute("asOf", asOf);
        model.addAttribute("sections", assembler.sections(sheet));
        // The raw signed total, which must be zero. Shown because it is what
        // makes the balance sheet self-checking against the trial balance
        // (PRD §FR-6) — the presentation totals are not supposed to cancel,
        // and a reader who did not know that would think the page was wrong.
        model.addAttribute("signedTotal", MoneyFormat.format(sheet.signedTotalMinor()));
        model.addAttribute("balanced", sheet.balanced());

        return "reports/balance-sheet";
    }

    /**
     * Expenses over a range (PRD §FR-5).
     *
     * <p>Both bounds default to the current month rather than being required,
     * so the link in the navigation leads somewhere useful. A reversed range
     * is reported on the page instead of throwing: {@code DateRange} refuses
     * it deliberately (swapping would answer a different question), and a
     * member who typed the dates in the wrong boxes should see why, not an
     * error page.
     */
    @GetMapping("/expenses")
    String expenses(@AuthenticationPrincipal AuthenticatedMember member,
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    Model model) {

        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today;

        model.addAttribute(UiModel.NAV, UiModel.NAV_REPORTS);
        model.addAttribute("report", "expenses");
        model.addAttribute("from", start);
        model.addAttribute("to", end);

        if (start.isAfter(end)) {
            model.addAttribute("rangeError",
                    "The 'from' date is after the 'to' date. Swap them to see the summary.");
            model.addAttribute("rows", java.util.List.of());
            model.addAttribute("total", MoneyFormat.format(0L));
            model.addAttribute("empty", true);
            return "reports/expenses";
        }

        ExpenseSummary summary = reportingService.expenseSummary(
                member.householdId(), new DateRange(start, end));

        model.addAttribute("rows", assembler.expenseRows(summary));
        model.addAttribute("total", MoneyFormat.format(summary.totalMinor()));
        model.addAttribute("empty", summary.isEmpty());

        return "reports/expenses";
    }

    /**
     * The trial balance (PRD §FR-6: "this exists to be demonstrable").
     *
     * <p>Household-scoped, like everything else. The global integrity check
     * that sweeps every household is a scheduled operator job with no
     * endpoint, and it stays that way: a member asking whether their books
     * balance is a different question from an operator asking whether the
     * database is intact.
     */
    @GetMapping("/trial-balance")
    String trialBalance(@AuthenticationPrincipal AuthenticatedMember member, Model model) {
        UUID householdId = member.householdId();
        TrialBalance trialBalance = reportingService.trialBalance(householdId);

        model.addAttribute(UiModel.NAV, UiModel.NAV_REPORTS);
        model.addAttribute("report", "trial-balance");
        model.addAttribute("trialBalance", assembler.trialBalanceView(trialBalance));

        return "reports/trial-balance";
    }
}
