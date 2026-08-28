package com.householdledger.identity.internal;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, verifies, rotates and revokes refresh tokens (PRD §FR-1:
 * "rotating refresh token (7 days), refresh tokens persisted and
 * revocable").
 *
 * <p>Two deliberate choices:
 *
 * <p><b>The raw token is never stored.</b> A 256-bit random value is
 * generated, returned to the caller once, and only its SHA-256 hash is
 * persisted. A stolen database therefore yields nothing replayable.
 * SHA-256 rather than bcrypt here on purpose — unlike a password, this
 * token is already high-entropy random, so a slow KDF buys no resistance to
 * guessing and would only add latency to every refresh.
 *
 * <p><b>Lookup is by hash, and hashing is deterministic</b>, which is what
 * makes a single indexed query possible (see V2 migration) rather than
 * scanning every row and bcrypt-comparing.
 */
@Component
class RefreshTokenStore {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final Duration refreshTokenTtl;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    RefreshTokenStore(RefreshTokenRepository repository, JwtProperties properties, Clock clock) {
        this.repository = repository;
        this.refreshTokenTtl = Duration.ofDays(properties.getRefreshTokenTtlDays());
        this.clock = clock;
    }

    /**
     * Creates and persists a new refresh token.
     *
     * @return the RAW token, which the caller must hand to the client
     *         immediately — it is unrecoverable afterwards
     */
    String issue(UUID memberId) {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        RefreshTokenEntity entity = new RefreshTokenEntity(
                UUID.randomUUID(), memberId, hash(rawToken), clock.instant().plus(refreshTokenTtl));
        repository.save(entity);

        return rawToken;
    }

    /** Returns the stored token only if it exists and is neither revoked nor expired. */
    Optional<RefreshTokenEntity> findUsable(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTokenHash(hash(rawToken))
                .filter(token -> token.isUsableAt(clock.instant()));
    }

    /**
     * Revokes a token if it is currently usable, reporting whether anything
     * was revoked. Revoking an unknown, expired, or already-revoked token is
     * not an error — logout must be idempotent (see
     * {@code IdentityService#logout}).
     */
    boolean revoke(String rawToken) {
        return findUsable(rawToken)
                .map(token -> {
                    token.revokeAt(clock.instant());
                    repository.save(token);
                    return true;
                })
                .orElse(false);
    }

    /**
     * The rotation step: revoke the presented token and issue its
     * replacement. Callers run this inside a transaction so the old token
     * cannot survive a failure that prevents the new one being written.
     */
    String rotate(RefreshTokenEntity current, UUID memberId) {
        current.revokeAt(clock.instant());
        repository.save(current);
        return issue(memberId);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
