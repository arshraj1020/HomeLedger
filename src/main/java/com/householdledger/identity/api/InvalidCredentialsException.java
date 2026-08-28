package com.householdledger.identity.api;

/**
 * Thrown when login fails. Deliberately carries no detail about *why* —
 * unknown email and wrong password produce the identical exception and the
 * identical HTTP response, so the endpoint cannot be used to enumerate which
 * email addresses are registered.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
