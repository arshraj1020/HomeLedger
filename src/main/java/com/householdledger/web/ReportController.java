package com.householdledger.web;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.reporting.api.ReportingService;
import com.householdledger.reporting.domain.DateRange;
import com.householdledger.web.dto.BalanceSheetResponse;
import com.householdledger.web.dto.ExpenseSummaryResponse;
import com.householdledger.web.dto.TrialBalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The three reporting endpoints of PRD §6.4.
 *
 * <p>All are readable by any authenticated member, not just ADMIN: PRD §FR-1
 * gives MEMBER the right to "read everything", and §2.1's viewer persona —
 * the household member who does not enter data but wants to know what the
 * card balance is — is precisely who these reports are for.
 *
 * <p><b>Household scoping.</b> Every call passes
 * {@code member.householdId()} from the verified JWT. There is no path or
 * query parameter naming a household, so a client cannot ask for someone
 * else's totals. This matters more here than on the row endpoints: an
 * aggregate leaks a *number* rather than a record, so a scoping mistake
 * would surface as a subtly wrong figure rather than as visibly foreign
 * data.
 *
 * <p><b>Reports do not 404.</b> A household with no accounts, or no spending
 * in a period, receives a successful empty report. "Nothing was spent in
 * March" is an answer; 404 would wrongly suggest the household or the report
 * itself does not exist.
 */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports", description = "Balance sheet, expense summary, and trial balance")
class ReportController {

    private final ReportingService reportingService;

    ReportController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/balance-sheet")
    @Operation(summary = "All accounts grouped by type with balances, optionally as of a date")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Balance sheet computed"),
            @ApiResponse(responseCode = "400", description = "Malformed asOf date", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    BalanceSheetResponse balanceSheet(
            @AuthenticationPrincipal AuthenticatedMember member,

            @Parameter(description = "Include postings dated on or before this date, inclusive. "
                    + "Omit for the current position.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        return BalanceSheetResponse.from(reportingService.balanceSheet(member.householdId(), asOf));
    }

    /**
     * Both bounds are required, matching PRD §6.4's
     * {@code ?from=&to=}. An expense summary without a range is not a summary
     * of anything in particular, so a missing parameter is a 400 rather than
     * a silent default to some arbitrary window.
     */
    @GetMapping("/expenses")
    @Operation(summary = "Totals grouped by expense account over an inclusive date range")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Summary computed; may be empty"),
            @ApiResponse(responseCode = "400", description = "Missing, malformed, or reversed date range", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    ExpenseSummaryResponse expenses(
            @AuthenticationPrincipal AuthenticatedMember member,

            @Parameter(description = "Start of the range, inclusive", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "End of the range, inclusive", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        // A reversed range throws IllegalArgumentException from DateRange and
        // surfaces as 400 through the existing LedgerExceptionHandler — the
        // same contract the Phase 5 transaction filter already uses.
        DateRange range = new DateRange(from, to);

        return ExpenseSummaryResponse.from(reportingService.expenseSummary(member.householdId(), range));
    }

    /**
     * Returns 200 even when the ledger is unbalanced: that is a successfully
     * computed report of a broken ledger, not a failed request, and a client
     * checking integrity needs to read the body rather than catch an error.
     */
    @GetMapping("/trial-balance")
    @Operation(summary = "Sum of every posting in the household, which must be zero")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trial balance computed"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    TrialBalanceResponse trialBalance(@AuthenticationPrincipal AuthenticatedMember member) {
        return TrialBalanceResponse.from(reportingService.trialBalance(member.householdId()));
    }
}
