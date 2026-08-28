package com.householdledger.ledger.api;

import java.util.UUID;

/** Thrown when a reversal transaction is itself targeted for reversal (PRD §FR-4). */
public class ReversalTransactionCannotBeReversedException extends RuntimeException {
    public ReversalTransactionCannotBeReversedException(UUID transactionId) {
        super("Transaction " + transactionId + " is itself a reversal and cannot be reversed");
    }
}
