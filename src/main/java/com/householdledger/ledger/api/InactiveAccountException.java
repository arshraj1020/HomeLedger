package com.householdledger.ledger.api;

import java.util.UUID;

/** Thrown when a posting is attempted against a deactivated account (PRD §FR-2). */
public class InactiveAccountException extends RuntimeException {
    public InactiveAccountException(UUID accountId) {
        super("Account " + accountId + " is deactivated and cannot accept new postings");
    }
}
