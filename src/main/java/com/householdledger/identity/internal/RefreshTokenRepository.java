package com.householdledger.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    /** Backed by the unique index added in V2__refresh_token_lookup_index.sql. */
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);
}
