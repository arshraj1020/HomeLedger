package com.householdledger.identity.internal;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.identity.domain.Member;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Form-login authentication for the browser UI (PRD §FR-7).
 *
 * <p><b>Why a custom provider rather than a {@code UserDetailsService}.</b>
 * The whole application scopes every read and write by
 * {@link AuthenticatedMember#householdId()}, taken from the authenticated
 * principal and never from anything a client sent (PRD §FR-1). The API gets
 * that principal from {@link JwtAuthenticationFilter}. If the browser chain
 * authenticated into Spring's stock {@code User} principal instead, every UI
 * controller would need a second way to discover the household — a lookup by
 * username on each request, or a household id smuggled through the session —
 * and that second path would be the one that eventually gets it wrong.
 * Producing the same principal type from both chains means UI controllers
 * write {@code @AuthenticationPrincipal AuthenticatedMember} exactly as the
 * API controllers do, and household scoping has one implementation.
 *
 * <p><b>No JWT is involved.</b> The browser session is a plain server-side
 * session; no access or refresh token is minted, stored in a cookie, or
 * handed to page JavaScript. PRD §FR-1's tokens remain an API concern, which
 * is what keeps them out of reach of any script running in the page.
 *
 * <p><b>Failure is uniform.</b> An unknown email and a wrong password both
 * raise {@link BadCredentialsException} with the same message, and the
 * password is still hashed and compared only when a member exists — matching
 * {@code IdentityServiceImpl.login} so the UI cannot be used to enumerate
 * registered addresses when the API cannot.
 *
 * <p>Lives in {@code identity.internal}: it depends on the member entity and
 * repository, which no other module may see (PRD §6.1). The only type it
 * publishes outward is {@link AuthenticatedMember}, already part of
 * {@code identity.api}.
 */
@Component
class BrowserAuthenticationProvider implements AuthenticationProvider {

    private static final String FAILURE_MESSAGE = "Invalid email or password";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    BrowserAuthenticationProvider(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        Object credentials = authentication.getCredentials();
        String rawPassword = credentials == null ? null : credentials.toString();

        Optional<MemberEntity> found = email == null || email.isBlank()
                ? Optional.empty()
                : memberRepository.findByEmailIgnoreCase(email.trim());

        MemberEntity entity = found
                .filter(candidate -> rawPassword != null
                        && passwordEncoder.matches(rawPassword, candidate.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException(FAILURE_MESSAGE));

        Member member = entity.toDomain();
        AuthenticatedMember principal = new AuthenticatedMember(
                member.id(), member.householdId(), member.email(), member.role());

        // Credentials are deliberately null on the result: the authenticated
        // token is stored in the HTTP session, and a session holding the
        // member's plaintext password would be a needless place to lose it.
        UsernamePasswordAuthenticationToken authenticated = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(member.role().authority())));
        authenticated.setDetails(authentication.getDetails());

        return authenticated;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
