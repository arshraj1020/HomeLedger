package com.householdledger.web.ui.view;

import java.util.List;

/**
 * The trial balance as a page shows it (PRD §FR-6: "this exists to be
 * demonstrable").
 *
 * <p>{@code postingCount} is shown next to the total on purpose. Zero is the
 * answer for a healthy ledger and also the answer for an empty one, and a
 * page that showed only "0" would be presenting the absence of data as proof
 * of correctness.
 */
public record TrialBalanceView(
        String total,
        long totalMinor,
        long postingCount,
        boolean balanced,
        boolean emptyLedger,
        List<UnbalancedRow> unbalanced) {

    public TrialBalanceView {
        unbalanced = List.copyOf(unbalanced);
    }
}
