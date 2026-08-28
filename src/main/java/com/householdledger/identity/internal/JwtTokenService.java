package com.householdledger.identity.internal;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.identity.api.InvalidCredentialsException;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies the 15-minute access token (PRD §FR-1).
 *
 * <p>The household id is a signed claim. That is what makes the scoping
 * guarantee real: a caller cannot change which household they act on
 * without forging the signature, so putting another household's id in a URL
 * path achieves nothing (PRD §FR-1, §9).
 *
 * <p>A {@link Clock} is injected rather than calling {@code Instant.now()}
 * directly so expiry can be tested deterministically instead of with sleeps.
 */
@Component
class JwtTokenService {

    static final String CLAIM_HOUSEHOLD_ID = "householdId";
    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;
    private final Clock clock;

    JwtTokenService(JwtProperties properties, Clock clock) {
        properties.validate();
        this.signingKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofMinutes(properties.getAccessTokenTtlMinutes());
        this.clock = clock;
    }

    String issueAccessToken(Member member) {
        Instant now = clock.instant();
        Instant expiry = now.plus(accessTokenTtl);

        return Jwts.builder()
                .subject(member.id().toString())
                .claim(CLAIM_HOUSEHOLD_ID, member.householdId().toString())
                .claim(CLAIM_EMAIL, member.email())
                .claim(CLAIM_ROLE, member.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    long accessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }

    /**
     * Verifies signature and expiry, then rebuilds the principal from the
     * signed claims.
     *
     * @throws InvalidCredentialsException for any invalid token — expired,
     *         tampered, wrong key, malformed, or missing required claims.
     *         One exception type for all of them: a caller learns only that
     *         the token is unusable, never why.
     */
    AuthenticatedMember parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidCredentialsException();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new AuthenticatedMember(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(CLAIM_HOUSEHOLD_ID, String.class)),
                    claims.get(CLAIM_EMAIL, String.class),
                    Role.valueOf(claims.get(CLAIM_ROLE, String.class)));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw new InvalidCredentialsException();
        }
    }
}
