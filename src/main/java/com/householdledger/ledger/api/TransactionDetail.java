package com.householdledger.ledger.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A transaction with its full posting detail, as PRD §6.4 specifies for
 * {@code GET /api/transactions/{id}} and as the response to recording one.
 *
 * <p>There is no amount field, deliberately — consistent with the domain
 * model (PRD §3.1: a transaction "has no amount field"; the amount is an
 * emergent property of its postings). A client that wants a headline figure
 * sums the positive postings, exactly as the ledger does.
 *
 * @param reversed              whether a reversal of this transaction exists
 *                              (PRD §FR-4: reversible exactly once)
 * @param reversesTransactionId set when this transaction is itself a
 *                              reversal, linking back to the original
 */
public record TransactionDetail(
        UUID id,
        LocalDate occurredOn,
        String description,
        UUID createdBy,
        List<PostingDetail> postings,
        boolean reversed,
        UUID reversesTransactionId) {

    /** True when this transaction was created by reversing another. */
    public boolean isReversal() {
        return reversesTransactionId != null;
    }
}
