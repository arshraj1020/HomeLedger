package com.householdledger.ledger.api;

/**
 * Thrown when an account name is already taken within the household
 * (PRD §FR-2: "a name unique within its household").
 *
 * <p>The database enforces this too via {@code UNIQUE (household_id, name)}
 * from the V1 baseline; this gives the service layer a clean error instead
 * of a constraint-violation stack trace, in the same spirit as the
 * three-layer invariant enforcement in PRD §3.2.
 */
public class AccountNameAlreadyExistsException extends RuntimeException {
    public AccountNameAlreadyExistsException(String name) {
        super("An account named '" + name + "' already exists in this household");
    }
}
