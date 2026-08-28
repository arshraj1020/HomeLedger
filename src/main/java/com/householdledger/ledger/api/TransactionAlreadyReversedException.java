package com.householdledger.ledger.api;

import java.util.UUID;

/** Thrown on a second reversal attempt against the same transaction (PRD §FR-4: maps to 409 Conflict). */
public class TransactionAlreadyReversedException extends RuntimeException {
    public TransactionAlreadyReversedException(UUID transactionId) {
        super("Transaction " + transactionId + " has already been reversed");
    }
}
