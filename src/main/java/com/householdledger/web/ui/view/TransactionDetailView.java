package com.householdledger.web.ui.view;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A transaction with its postings, as the detail page shows it (PRD §FR-7:
 * "transaction detail with reverse action").
 *
 * <p>{@code reversible} is computed once, here, from the two rules of PRD
 * §FR-4 — a transaction may be reversed exactly once, and a reversal may not
 * itself be reversed. The page uses it to decide whether to offer the button.
 * The service enforces the same rules independently, so hiding the button is
 * a courtesy to the reader, never the protection.
 *
 * <p>{@code totalDebits} equals {@code totalCredits} for every transaction
 * that exists; both are shown so a reader can see that rather than take it on
 * trust (PRD §FR-6's spirit, applied to a single entry).
 */
public record TransactionDetailView(
        UUID id,
        LocalDate occurredOn,
        String description,
        List<PostingRow> postings,
        String totalDebits,
        String totalCredits,
        boolean balanced,
        boolean reversed,
        boolean reversal,
        UUID reversesTransactionId,
        boolean reversible) {

    public TransactionDetailView {
        postings = List.copyOf(postings);
    }
}
