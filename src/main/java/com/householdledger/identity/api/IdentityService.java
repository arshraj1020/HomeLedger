package com.householdledger.identity.api;

/**
 * Authentication operations exposed by the {@code identity} module
 * (PRD §FR-1, §6.4). Other modules depend on this interface and the
 * framework-free types in {@code identity.domain}; they never reach
 * {@code identity.internal} (enforced by {@code ModuleBoundaryArchTest}).
 */
public interface IdentityService {

    /**
     * Verifies email and password (bcrypt) and issues a fresh token pair.
     *
     * @throws InvalidCredentialsException if the email is unknown or the
     *         password does not match — the same exception either way, so
     *         registered addresses cannot be enumerated
     */
    TokenPair login(String email, String rawPassword);

    /**
     * Exchanges a valid refresh token for a new pair, rotating the refresh
     * token: the presented token is revoked in the same database
     * transaction that issues its replacement, so a token can be used at
     * most once (PRD §FR-1: "rotating refresh token").
     *
     * @throws InvalidRefreshTokenException if unknown, already used,
     *         revoked, or expired
     */
    TokenPair refresh(String rawRefreshToken);

    /**
     * Revokes the presented refresh token. Idempotent: logging out with a
     * token that is already revoked or unknown succeeds silently, so a
     * client can always reach a signed-out state and the endpoint cannot be
     * used to probe which tokens exist.
     *
     * <p>Scope is this token only, not every session belonging to the
     * member — PRD §FR-1 requires refresh tokens be "revocable", not a
     * global sign-out.
     */
    void logout(String rawRefreshToken);

    /**
     * Resolves a verified access token into the principal used for
     * household scoping.
     *
     * @throws InvalidCredentialsException if the token is malformed,
     *         tampered with, expired, or signed with the wrong key
     */
    AuthenticatedMember authenticate(String accessToken);
}
