package com.householdledger.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The bcrypt encoder, on its own, depending on nothing.
 *
 * <p>Phase 7 is what forced this out of {@link SecurityConfig}. The browser
 * login chain needs an {@link org.springframework.security.authentication.AuthenticationProvider},
 * that provider needs a {@link PasswordEncoder} to check the stored hash, and
 * {@code SecurityConfig} needs the provider to build the filter chain. With
 * the encoder still declared on {@code SecurityConfig} the graph closes:
 * {@code SecurityConfig -> BrowserAuthenticationProvider -> PasswordEncoder
 * (on SecurityConfig)}, and Spring fails at startup with
 * {@code BeanCurrentlyInCreationException}.
 *
 * <p>That is exactly the failure Phase 2 hit with the {@code Clock} bean, and
 * the fix is the same one: a bean two collaborators need does not belong on a
 * configuration class that is itself one of those collaborators. Using
 * {@code @Lazy} or {@code allow-circular-references} would hide the cycle
 * rather than remove it.
 *
 * <p>Behaviour is unchanged — the same {@link BCryptPasswordEncoder} at the
 * same default strength (10), chosen in Phase 2 as costly to brute-force
 * while keeping login latency inside the PRD §5 target. Only the declaring
 * class moved, so every existing hash still verifies.
 */
@Configuration
class PasswordEncoderConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
