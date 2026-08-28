package com.householdledger.web.dto;

import com.householdledger.ledger.api.TransactionDetail;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response shape for a transaction, matching the sample in PRD §6.4.
 *
 * <p>No amount field — the amount is emergent from the postings (PRD §3.1),
 * and inventing a summary figure here would create a second source of truth
 * that could disagree with them.
 */
public record TransactionResponse(
        UUID id,
        LocalDate occurredOn,
        String description,
        List<PostingResponse> postings,
        boolean reversed,
        UUID reversesTransactionId) {

    public static TransactionResponse from(TransactionDetail detail) {
        return new TransactionResponse(
                detail.id(),
                detail.occurredOn(),
                detail.description(),
                detail.postings().stream().map(PostingResponse::from).toList(),
                detail.reversed(),
                detail.reversesTransactionId());
    }

    /** One posting leg: account, its name, and the signed amount in minor units. */
    public record PostingResponse(UUID accountId, String accountName, long amountMinor) {

        static PostingResponse from(com.householdledger.ledger.api.PostingDetail detail) {
            return new PostingResponse(detail.accountId(), detail.accountName(), detail.amountMinor());
        }
    }
}
