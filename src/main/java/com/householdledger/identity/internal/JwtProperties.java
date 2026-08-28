package com.householdledger.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings bound from {@code security.jwt.*} (see application.yml).
 *
 * <p>PRD §5 requires "No plaintext secrets in repo; JWT secret via env var".
 * The secret is therefore sourced from {@code ${JWT_SECRET}}, and
 * {@link #validate()} refuses to start with a key too short to be safe for
 * HMAC-SHA256 — failing loudly at boot rather than silently issuing weakly
 * signed tokens.
 *
 * <p>Defaults match PRD §FR-1: 15-minute access token, 7-day refresh token.
 */
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    /** Minimum key length for HMAC-SHA256, per RFC 7518 §3.2 (256 bits). */
    static final int MINIMUM_SECRET_BYTES = 32;

    private String secret;
    private long accessTokenTtlMinutes = 15;
    private long refreshTokenTtlDays = 7;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenTtlMinutes() {
        return accessTokenTtlMinutes;
    }

    public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) {
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    public long getRefreshTokenTtlDays() {
        return refreshTokenTtlDays;
    }

    public void setRefreshTokenTtlDays(long refreshTokenTtlDays) {
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    /**
     * @throws IllegalStateException if the secret is missing or too short
     */
    public void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.secret is not set. Provide it via the JWT_SECRET environment variable.");
        }
        int length = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret must be at least " + MINIMUM_SECRET_BYTES
                            + " bytes for HMAC-SHA256; got " + length
                            + ". Set a longer JWT_SECRET.");
        }
        if (accessTokenTtlMinutes <= 0 || refreshTokenTtlDays <= 0) {
            throw new IllegalStateException("JWT token lifetimes must be positive");
        }
    }
}
