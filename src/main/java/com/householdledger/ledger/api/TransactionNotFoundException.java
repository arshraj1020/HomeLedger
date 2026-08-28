package com.householdledger.ledger.api;

import java.util.UUID;

/** Thrown when a transaction id does not resolve within the caller's household. See {@link AccountNotFoundException} for the same 404-not-403 rationale. */
public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(UUID transactionId) {
        super("No transaction " + transactionId + " in this household");
    }
}
