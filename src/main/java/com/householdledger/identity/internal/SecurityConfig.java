package com.householdledger.identity.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security wiring: one chain for the stateless API, one for the
 * browser UI (PRD §FR-1, §FR-7).
 *
 * <p><b>Why two chains rather than one.</b> The two front doors have
 * genuinely opposite requirements, and every attempt to serve both from one
 * configuration ends in weakening one of them. The API is stateless and
 * authenticated by a bearer token, so it has no cookie for an attacker's site
 * to ride and CSRF protection has nothing to protect; it must answer an
 * anonymous request with 401 so a client can react. The UI is authenticated
 * by a session cookie, which is precisely the thing CSRF protection exists
 * for, and it must answer an anonymous request with a redirect to a login
 * page, because a browser cannot do anything useful with a bare 401. Merged,
 * one of those becomes wrong: either the UI runs without CSRF protection, or
 * the API starts issuing redirects to HTML.
 *
 * <p>{@code securityMatcher} on the first chain is what keeps them apart. The
 * API chain's behaviour is byte-for-byte what it was before Phase 7 — same
 * public paths, same stateless policy, same 401 entry point, same JWT filter
 * — it simply no longer claims every URL in the application.
 *
 * <p><b>The password encoder is deliberately not declared here</b> (see
 * {@link PasswordEncoderConfig}): this class depends on
 * {@link BrowserAuthenticationProvider}, which depends on the encoder, so
 * declaring it here would close a bean cycle.
 */
@Configuration
@EnableWebSecurity
// Enables @PreAuthorize so account-management endpoints can be restricted to
// ADMIN per PRD §FR-1, keeping the rule next to the endpoint it guards rather
// than in a path-matching list here that would drift as the app grows. It
// applies to the UI controllers too, so one annotation protects both doors.
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final BrowserAuthenticationProvider browserAuthenticationProvider;

    SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                   BrowserAuthenticationProvider browserAuthenticationProvider) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.browserAuthenticationProvider = browserAuthenticationProvider;
    }

    /**
     * The machine-facing surface: the JSON API, the OpenAPI documents, Swagger
     * UI and the actuator.
     *
     * <p>The documentation and actuator paths are matched here rather than by
     * the browser chain so their treatment is unchanged from Phase 2 — no
     * session, no CSRF token, no redirect to a login form, and no Content
     * Security Policy that Swagger UI's own assets would have to satisfy. The
     * authorisation rules inside are exactly as they were: health and info
     * open, everything else under {@code /actuator} authenticated.
     */
    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/v3/api-docs/**", "/actuator/**")
                // No browser sessions or cookies are used here — the bearer
                // token is the whole authentication story — so CSRF protection
                // has nothing to protect and is turned off deliberately, not
                // carelessly. The browser chain below enables it.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public: authentication itself, API docs, and health.
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Everything else requires a valid access token. Note
                        // this is deny-by-default: an endpoint added in a later
                        // phase is protected unless explicitly opened.
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * The browser-facing surface: everything the Thymeleaf UI serves.
     *
     * <p>Authentication is a server-side session established by form login.
     * No JWT is minted for the browser and none is written to a cookie or
     * into the page, so there is no token for client-side JavaScript to read
     * or for an XSS payload to exfiltrate — the session cookie is
     * {@code HttpOnly} by the servlet container's default and is the only
     * credential the browser holds.
     *
     * <p>{@link BrowserAuthenticationProvider} is registered explicitly rather
     * than relying on a {@code UserDetailsService}, so the principal in the
     * session is the same {@code AuthenticatedMember} the API produces and
     * household scoping has a single implementation.
     *
     * <p>CSRF protection is on, with Spring Security's default synchroniser
     * token in the session. Thymeleaf's {@code th:action} adds the hidden
     * field to every form automatically, which is why no template has to
     * remember to.
     */
    @Bean
    @Order(2)
    SecurityFilterChain browserSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authenticationProvider(browserAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login").permitAll()
                        .requestMatchers("/css/**", "/favicon.ico").permitAll()
                        // Boot's error dispatch must be reachable, or a failure
                        // inside a protected page becomes a redirect loop
                        // instead of an error page.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        // alwaysUse=true: after signing in the member lands on
                        // the dashboard rather than on whatever asset request
                        // happened to be saved, which is the usual cause of
                        // "logged in and got a stylesheet".
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        // With CSRF enabled Spring Security matches this
                        // URL for POST only, so the token is required: a bare
                        // <img src="/logout"> on another site must not be able
                        // to sign a member out.
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?loggedOut")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session
                        // A new session id is issued on login, so a session id
                        // planted before authentication cannot be reused after.
                        .sessionFixation(sessionFixation -> sessionFixation.changeSessionId())
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp
                                // The UI has no inline scripts, no inline
                                // styles and no third-party assets, so it can
                                // afford the strict policy that makes most
                                // injected markup inert. Swagger UI is served
                                // by the API chain above and is unaffected.
                                .policyDirectives("default-src 'self'; "
                                        + "script-src 'none'; "
                                        + "object-src 'none'; "
                                        + "base-uri 'self'; "
                                        + "form-action 'self'; "
                                        + "frame-ancestors 'none'")));

        return http.build();
    }
}
