package com.householdledger.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the application {@link Clock}.
 *
 * <p>Lives in {@code shared} — PRD §6.1 describes that module as "common
 * errors, base types, config", which is exactly what a clock is. It was
 * introduced in {@code identity.internal} in Phase 2 when identity was its
 * only consumer; Phase 4 gives the ledger module a genuine need for it too
 * (PRD §FR-3's "date not in the future beyond a small tolerance" has to be
 * evaluated against *something*), and a bean the ledger depends on has no
 * business living behind another module's internal boundary.
 *
 * <p>It is also deliberately a configuration class with no dependencies of
 * its own. Declaring this bean on a class that itself injects other beans is
 * what produced a {@code BeanCurrentlyInCreationException} in Phase 2
 * (SecurityConfig → filter → token service → clock → SecurityConfig); an
 * isolated holder cannot close a cycle.
 *
 * <p>Injecting a {@code Clock} rather than calling {@code LocalDate.now()}
 * inline is what makes date-boundary rules testable at their exact edge —
 * "one day in the future is accepted, two days is not" — instead of
 * approximately, or with sleeps.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
