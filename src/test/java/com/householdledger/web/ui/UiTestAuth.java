package com.householdledger.web.ui;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.identity.domain.Member;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

/**
 * Builds the authentication a signed-in browser request carries.
 *
 * <p>Deliberately constructs the same principal type and the same authority
 * that {@code BrowserAuthenticationProvider} produces, rather than using
 * {@code SecurityMockMvcRequestPostProcessors.user(...)}, which would put
 * Spring's stock {@code User} in the context. A test authenticated with the
 * wrong principal type would pass while
 * {@code @AuthenticationPrincipal AuthenticatedMember} silently arrived null
 * in production — the exact failure these tests exist to catch. The real
 * login path is exercised end to end in {@code UiSecurityIT}.
 */
final class UiTestAuth {

    private UiTestAuth() {
    }

    static RequestPostProcessor as(Member member) {
        AuthenticatedMember principal = new AuthenticatedMember(
                member.id(), member.householdId(), member.email(), member.role());

        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority(member.role().authority()))));
    }
}
