package com.householdledger.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the application {@link Clock}.
 *
 * <p>Deliberately its own configuration class rather than a bean method on
 * {@code SecurityConfig}. {@code SecurityConfig} constructor-injects
 * {@link JwtAuthenticationFilter}, which depends on {@link JwtTokenService},
 * which depends on {@code Clock} — so declaring the clock there closed a
 * cycle (SecurityConfig → filter → token service → clock → SecurityConfig)
 * and Spring failed with {@code BeanCurrentlyInCreationException}. Keeping
 * the clock in a configuration class with no dependencies of its own breaks
 * that cycle at the source, rather than papering over it with {@code @Lazy}
 * or by allowing circular references.
 *
 * <p>Injecting a {@code Clock} at all — instead of calling
 * {@code Instant.now()} inline — is what lets token issuance, expiry and
 * refresh-token lifetimes be tested deterministically with a fixed clock
 * rather than with sleeps.
 */
@Configuration
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
