package com.householdledger.ledger.domain;

/**
 * Thrown when a {@link Transaction} is constructed from a set of postings
 * that does not satisfy the invariant (PRD §3.2): fewer than two postings,
 * or signed amounts that do not sum to exactly zero. This is layer 1 of the
 * three independent enforcement points described in PRD §3.2 — the service
 * layer (layer 2) catches this and turns it into a clean 422 API error
 * (PRD §6.4 error contract); the database trigger (layer 3) is the backstop
 * that holds regardless of which code path attempted the write.
 */
public class UnbalancedTransactionException extends RuntimeException {

    public UnbalancedTransactionException(String message) {
        super(message);
    }
}
