package com.householdledger.identity.internal;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.identity.api.InvalidCredentialsException;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for access-token issuing and verification (PRD §FR-1).
 *
 * <p>No Spring context and no database: a fixed {@link Clock} makes expiry
 * deterministic, so the 15-minute lifetime is asserted exactly rather than
 * approximated with sleeps.
 *
 * <p>The security-critical assertions here are the negative ones — a token
 * signed with a different key, or altered after signing, must be rejected.
 * Those are what make the household claim trustworthy enough to scope every
 * request on (PRD §FR-1, §9).
 */
class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hmac-sha256";
    private static final String OTHER_SECRET = "a-completely-different-secret-key-also-32-bytes-plus";
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    private final UUID memberId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    private JwtProperties properties(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setAccessTokenTtlMinutes(15);
        properties.setRefreshTokenTtlDays(7);
        return properties;
    }

    private JwtTokenService serviceAt(Instant instant, String secret) {
        return new JwtTokenService(properties(secret), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private JwtTokenService service() {
        return serviceAt(NOW, SECRET);
    }

    private Member member(Role role) {
        return new Member(memberId, householdId, "Papa", "papa@example.com", role);
    }

    @Test
    void issuedTokenRoundTripsToTheSamePrincipal() {
        JwtTokenService service = service();

        AuthenticatedMember principal = service.parseAccessToken(service.issueAccessToken(member(Role.ADMIN)));

        assertThat(principal.memberId()).isEqualTo(memberId);
        assertThat(principal.householdId()).isEqualTo(householdId);
        assertThat(principal.email()).isEqualTo("papa@example.com");
        assertThat(principal.role()).isEqualTo(Role.ADMIN);
        assertThat(principal.isAdmin()).isTrue();
    }

    @Test
    void rolePropagatesForNonAdminMembers() {
        JwtTokenService service = service();

        AuthenticatedMember principal = service.parseAccessToken(service.issueAccessToken(member(Role.MEMBER)));

        assertThat(principal.role()).isEqualTo(Role.MEMBER);
        assertThat(principal.isAdmin()).isFalse();
    }

    @Test
    void accessTokenTtlMatchesThePrdFifteenMinutes() {
        assertThat(service().accessTokenTtlSeconds()).isEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    @Test
    void tokenIsStillValidJustBeforeExpiry() {
        String token = serviceAt(NOW, SECRET).issueAccessToken(member(Role.MEMBER));

        JwtTokenService almostExpired = serviceAt(NOW.plus(Duration.ofMinutes(14)), SECRET);

        assertThat(almostExpired.parseAccessToken(token).memberId()).isEqualTo(memberId);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = serviceAt(NOW, SECRET).issueAccessToken(member(Role.MEMBER));

        JwtTokenService later = serviceAt(NOW.plus(Duration.ofMinutes(16)), SECRET);

        assertThatThrownBy(() -> later.parseAccessToken(token))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void tokenSignedWithADifferentKeyIsRejected() {
        // The whole scoping guarantee rests on this: without the signing key
        // an attacker cannot mint a token naming another household.
        String foreignToken = serviceAt(NOW, OTHER_SECRET).issueAccessToken(member(Role.ADMIN));

        assertThatThrownBy(() -> service().parseAccessToken(foreignToken))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = service().issueAccessToken(member(Role.MEMBER));
        // Flip a character in the payload segment; the signature no longer matches.
        String[] parts = token.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 2)
                + (parts[1].endsWith("A") ? "B" : "A");
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> service().parseAccessToken(tampered))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void malformedNullAndBlankTokensAreRejected() {
        JwtTokenService service = service();

        assertThatThrownBy(() -> service.parseAccessToken("not-a-jwt"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.parseAccessToken(null))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.parseAccessToken("   "))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.parseAccessToken("a.b.c"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void constructionRejectsAMissingSecret() {
        JwtProperties missing = properties(null);

        assertThatThrownBy(() -> new JwtTokenService(missing, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void constructionRejectsAShortSecret() {
        // PRD §5 requires the secret come from an env var; a too-short value
        // would still sign tokens but weakly, so it fails at startup instead.
        JwtProperties tooShort = properties("short");

        assertThatThrownBy(() -> new JwtTokenService(tooShort, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void constructionRejectsNonPositiveLifetimes() {
        JwtProperties zeroTtl = properties(SECRET);
        zeroTtl.setAccessTokenTtlMinutes(0);

        assertThatThrownBy(() -> new JwtTokenService(zeroTtl, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");
    }
}
