package com.householdledger.identity.api;

/**
 * Thrown when a refresh token is unknown, already used (rotated away),
 * explicitly revoked by logout, or past its expiry. As with
 * {@link InvalidCredentialsException}, all four cases are indistinguishable
 * to the caller by design.
 */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("Refresh token is invalid, expired, or has been revoked");
    }
}
