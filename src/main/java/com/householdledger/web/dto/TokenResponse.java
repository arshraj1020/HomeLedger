package com.householdledger.web.dto;

import com.householdledger.identity.api.TokenPair;

/**
 * Response body for login and refresh. {@code tokenType} is included so
 * clients can construct the Authorization header without hard-coding
 * "Bearer".
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn) {

    public static TokenResponse from(TokenPair pair) {
        return new TokenResponse(
                pair.accessToken(),
                pair.refreshToken(),
                "Bearer",
                pair.accessTokenExpiresInSeconds());
    }
}
