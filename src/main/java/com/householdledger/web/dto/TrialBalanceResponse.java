package com.householdledger.web.dto;

import com.householdledger.reporting.api.TrialBalance;

import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /api/reports/trial-balance} (PRD §6.4, §FR-6).
 *
 * <p>PRD §FR-6: "This exists to be demonstrable." So the response says more
 * than "zero": it reports how many postings were summed (an empty ledger
 * also sums to zero, and only one of those is evidence), and names any
 * offending transactions if the invariant has somehow been broken.
 *
 * <p>Returns 200 even when unbalanced. A failing trial balance is a
 * successfully computed report of a broken ledger, not a failed request —
 * and a client polling for integrity needs to read the body, not catch an
 * error.
 */
public record TrialBalanceResponse(
        long totalMinor,
        long postingCount,
        boolean balanced,
        boolean emptyLedger,
        List<UnbalancedEntry> unbalancedTransactions) {

    public record UnbalancedEntry(UUID transactionId, long offByMinor) {
    }

    public static TrialBalanceResponse from(TrialBalance trialBalance) {
        return new TrialBalanceResponse(
                trialBalance.totalMinor(),
                trialBalance.postingCount(),
                trialBalance.balanced(),
                trialBalance.isEmptyLedger(),
                trialBalance.unbalancedTransactions().stream()
                        .map(t -> new UnbalancedEntry(t.transactionId(), t.offByMinor()))
                        .toList());
    }
}
