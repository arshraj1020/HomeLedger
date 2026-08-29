package com.householdledger.identity.internal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default profile creates nothing.
 *
 * <p>This is the test that matters most for anything deployed. It is
 * deliberately hostile: the bootstrap properties are set to perfectly valid
 * values, so the only thing standing between this context and a new ADMIN
 * account is the absence of the {@code dev} profile. If the {@code @Profile}
 * annotation were dropped, or the runner were made conditional on the
 * properties being present instead of on the profile, this fails.
 *
 * <p>It also asserts on the bean itself rather than only on the database. A
 * bootstrap bean that exists but happened not to write anything during this
 * run would still be a bootstrap bean in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DevelopmentAdminBootstrapDisabledIT {

    private static final String EMAIL = "should-never-exist@example.test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("household_ledger_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Complete and valid, and still must not be acted on.
        registry.add("bootstrap.dev-admin.email", () -> EMAIL);
        registry.add("bootstrap.dev-admin.password", () -> "a-perfectly-usable-password");
        registry.add("bootstrap.dev-admin.household-name", () -> "Should Never Exist Household");
    }

    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void theDevProfileIsNotActive() {
        assertThat(environment.getActiveProfiles())
                .doesNotContain(DevelopmentAdminBootstrap.PROFILE);
    }

    @Test
    void theBootstrapBeanDoesNotExistAtAll() {
        assertThat(context.getBeanNamesForType(DevelopmentAdminBootstrap.class))
                .as("the runner must not be in the context outside the dev profile")
                .isEmpty();

        assertThat(context.getBeanNamesForType(DevelopmentAdminProperties.class))
                .as("its properties are registered by the runner, so they should go with it")
                .isEmpty();
    }

    @Test
    void noApplicationRunnerCreatesMembers() {
        assertThat(context.getBeansOfType(ApplicationRunner.class).values())
                .noneSatisfy(runner -> assertThat(runner).isInstanceOf(DevelopmentAdminBootstrap.class));
    }

    @Test
    void nothingWasWrittenToTheDatabase() {
        Long matching = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member WHERE lower(email) = lower(?)", Long.class, EMAIL);

        assertThat(matching).isZero();

        Long households = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM household WHERE name = ?", Long.class, "Should Never Exist Household");

        assertThat(households).isZero();
    }
}
