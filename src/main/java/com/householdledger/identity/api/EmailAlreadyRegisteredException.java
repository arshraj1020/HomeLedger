package com.householdledger.identity.api;

/**
 * Thrown when provisioning a member with an email that already exists.
 * The schema enforces this too (member.email UNIQUE, PRD §6.3); this gives
 * the service layer a clean error rather than a constraint violation.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException(String email) {
        super("A member is already registered with email " + email);
    }
}
