package com.householdledger.identity.internal;

import com.householdledger.identity.api.*;
import com.householdledger.identity.domain.Member;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Package-private implementation of {@link IdentityService} (PRD §FR-1).
 * Spring instantiates package-private {@code @Service} beans, so the module
 * boundary in PRD §6.1 costs nothing here.
 */
@Service
class IdentityServiceImpl implements IdentityService {

    private final MemberRepository memberRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;

    IdentityServiceImpl(MemberRepository memberRepository, RefreshTokenStore refreshTokenStore,
                         JwtTokenService jwtTokenService, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.refreshTokenStore = refreshTokenStore;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public TokenPair login(String email, String rawPassword) {
        Optional<MemberEntity> found = email == null ? Optional.empty()
                : memberRepository.findByEmailIgnoreCase(email);

        // Compare against the stored hash only when a member exists. The
        // exception is identical either way, so an attacker cannot tell an
        // unknown address from a wrong password.
        MemberEntity member = found
                .filter(candidate -> rawPassword != null
                        && passwordEncoder.matches(rawPassword, candidate.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new);

        return issuePair(member.toDomain());
    }

    @Override
    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        RefreshTokenEntity current = refreshTokenStore.findUsable(rawRefreshToken)
                .orElseThrow(InvalidRefreshTokenException::new);

        MemberEntity member = memberRepository.findById(current.getMemberId())
                .orElseThrow(InvalidRefreshTokenException::new);

        // Rotation: the presented token is revoked and its replacement
        // issued in this same transaction, so a refresh token is usable at
        // most once (PRD §FR-1).
        String rotated = refreshTokenStore.rotate(current, member.getId());

        return new TokenPair(
                jwtTokenService.issueAccessToken(member.toDomain()),
                rotated,
                jwtTokenService.accessTokenTtlSeconds());
    }

    @Override
    @Transactional
    public void logout(String rawRefreshToken) {
        // Return value intentionally ignored: logout is idempotent, so an
        // unknown or already-revoked token is a success, not an error.
        refreshTokenStore.revoke(rawRefreshToken);
    }

    @Override
    public AuthenticatedMember authenticate(String accessToken) {
        return jwtTokenService.parseAccessToken(accessToken);
    }

    private TokenPair issuePair(Member member) {
        return new TokenPair(
                jwtTokenService.issueAccessToken(member),
                refreshTokenStore.issue(member.id()),
                jwtTokenService.accessTokenTtlSeconds());
    }
}
