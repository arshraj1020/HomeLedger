package com.householdledger.reporting.api;

import com.householdledger.ledger.api.UnbalancedTransaction;

import java.util.List;

/**
 * The sum of every posting in the household, which must be zero
 * (PRD §FR-6).
 *
 * <p>PRD §FR-6: "This exists to be demonstrable. It is the observable proof
 * that the invariant holds across the whole dataset, not just at write
 * time."
 *
 * @param postingCount how many postings were summed. Without it a zero total
 *                     is ambiguous: an empty ledger and a correct ledger both
 *                     report zero, and only one of them is evidence of
 *                     anything.
 * @param unbalancedTransactions the offenders, if the invariant has somehow
 *                     been broken. Always empty in a healthy ledger; carried
 *                     so the endpoint can name them rather than leaving an
 *                     operator to hunt.
 */
public record TrialBalance(
        long totalMinor,
        long postingCount,
        List<UnbalancedTransaction> unbalancedTransactions) {

    public TrialBalance {
        unbalancedTransactions = List.copyOf(unbalancedTransactions);
    }

    /**
     * A ledger is balanced when the postings sum to zero AND no individual
     * transaction is off. Both are checked because they can disagree: two
     * transactions wrong by +500 and -500 sum to zero while both are broken.
     */
    public boolean balanced() {
        return totalMinor == 0L && unbalancedTransactions.isEmpty();
    }

    public boolean isEmptyLedger() {
        return postingCount == 0L;
    }
}
