package com.householdledger.identity.api;

/**
 * A freshly issued access/refresh token pair (PRD §FR-1: 15-minute access
 * token, 7-day rotating refresh token).
 *
 * <p>{@code refreshToken} is the raw, single-use token handed to the client.
 * Only its hash is persisted, so this value cannot be recovered from the
 * database — losing it means re-authenticating, which is the intended
 * property.
 *
 * @param accessTokenExpiresInSeconds lifetime of the access token, so a
 *        client can schedule refresh without parsing the JWT itself
 */
public record TokenPair(String accessToken, String refreshToken, long accessTokenExpiresInSeconds) {
}
