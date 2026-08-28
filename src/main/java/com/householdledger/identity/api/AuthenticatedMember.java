package com.householdledger.identity.api;

import com.householdledger.identity.domain.Role;

import java.util.UUID;

/**
 * The authenticated principal, and the single source of truth for household
 * scoping (PRD §FR-1: "Every request is scoped to the authenticated
 * member's household. Cross-household access must be impossible even with a
 * forged ID in the path.").
 *
 * <p>The security-critical property is that {@link #householdId()} comes
 * from the verified JWT and never from a request path, query parameter or
 * body. Controllers pass this household id into the ledger and reporting
 * services; a caller who forges another household's id in a URL still has
 * their own household id here, so the lookup simply finds nothing and 404s
 * (PRD §9 — 404 rather than 403, so existence is not leaked).
 *
 * <p>Carries no credential material: no password hash, no raw token.
 */
public record AuthenticatedMember(UUID memberId, UUID householdId, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
