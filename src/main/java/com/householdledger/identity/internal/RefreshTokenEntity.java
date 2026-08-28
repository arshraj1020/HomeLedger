package com.householdledger.identity.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for the {@code refresh_token} table (PRD §6.3).
 *
 * <p>Only the SHA-256 hash of the token is stored — never the token itself.
 * A database dump therefore contains nothing that can be replayed as a
 * credential, which is the same reasoning that makes storing bcrypt hashes
 * rather than passwords non-negotiable.
 *
 * <p>Revocation is a timestamp rather than a row delete, keeping the table
 * consistent with the project's append-only stance (PRD §3.5): you can
 * always reconstruct when a session was ended.
 */
@Entity
@Table(name = "refresh_token")
class RefreshTokenEntity {

    @Id
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshTokenEntity() {
        // JPA
    }

    RefreshTokenEntity(UUID id, UUID memberId, String tokenHash, Instant expiresAt) {
        this.id = id;
        this.memberId = memberId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    UUID getMemberId() {
        return memberId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Usable exactly when it is neither revoked nor past expiry. */
    boolean isUsableAt(Instant now) {
        return !isRevoked() && !isExpiredAt(now);
    }

    void revokeAt(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }
}
