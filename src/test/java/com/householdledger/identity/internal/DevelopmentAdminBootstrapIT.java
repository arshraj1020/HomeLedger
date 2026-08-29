package com.householdledger.identity.internal;

import com.householdledger.identity.api.IdentityService;
import com.householdledger.identity.api.InvalidCredentialsException;
import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.api.TokenPair;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.DefaultAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code dev} bootstrap against a real database (PRD §FR-1).
 *
 * <p>Runs with {@code dev} active, so the runner has already executed once by
 * the time any test method starts — the context's own startup is the "first
 * run" under test.
 */
@SpringBootTest
@ActiveProfiles({"test", "dev"})
@Testcontainers
class DevelopmentAdminBootstrapIT {

    private static final String EMAIL = "dev-admin@example.test";
    private static final String PASSWORD = "a-password-only-this-developer-knows";
    private static final String HOUSEHOLD = "Bootstrap Test Household";

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

        // Supplied here rather than through the environment, so the test does
        // not depend on the developer's shell — and so nothing in the test
        // tree needs a credential of its own.
        registry.add("bootstrap.dev-admin.email", () -> EMAIL);
        registry.add("bootstrap.dev-admin.password", () -> PASSWORD);
        registry.add("bootstrap.dev-admin.household-name", () -> HOUSEHOLD);
    }

    @Autowired private DevelopmentAdminBootstrap bootstrap;
    @Autowired private MemberProvisioningService provisioningService;
    @Autowired private IdentityService identityService;
    @Autowired private AccountService accountService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------- first run

    @Test
    void startingUnderTheDevProfileCreatesTheHouseholdAndItsAdmin() {
        assertThat(householdCount(HOUSEHOLD)).isOne();

        Member admin = onlyMemberWithTheDevEmail();
        assertThat(admin.email()).isEqualTo(EMAIL);
        assertThat(admin.role())
                .as("the first member has to be able to manage accounts and members, PRD §FR-1")
                .isEqualTo(Role.ADMIN);
    }

    /**
     * The bootstrap goes through {@code MemberProvisioningService}, which is
     * also what seeds a new household's chart of accounts (PRD §FR-2). Getting
     * that for free is the reason this class writes no SQL of its own.
     */
    @Test
    void theBootstrappedHouseholdIsSeededWithItsChartOfAccounts() {
        List<Account> accounts = accountService.listAccounts(onlyMemberWithTheDevEmail().householdId());

        assertThat(accounts).extracting(Account::name)
                .contains(DefaultAccounts.CASH, DefaultAccounts.OPENING_BALANCES);
    }

    // -------------------------------------------------------- hashing

    /**
     * Read straight out of Postgres rather than through the domain, because
     * what matters is what was actually stored. {@code Member} deliberately
     * has no password field, so nothing above this line could tell the
     * difference between a hash and the plaintext.
     */
    @Test
    void thePasswordIsStoredAsABcryptHashAndNeverAsPlaintext() {
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM member WHERE lower(email) = lower(?)", String.class, EMAIL);

        assertThat(storedHash).isNotNull();
        assertThat(storedHash)
                .as("bcrypt hashes carry a $2a$/$2b$/$2y$ prefix")
                .matches("^\\$2[aby]\\$\\d{2}\\$.{53}$");
        assertThat(storedHash).isNotEqualTo(PASSWORD);
        assertThat(storedHash).doesNotContain(PASSWORD);

        assertThat(passwordEncoder.matches(PASSWORD, storedHash))
                .as("hashed by the application's own encoder, not by something the bootstrap invented")
                .isTrue();
        assertThat(passwordEncoder.matches("not-the-password", storedHash)).isFalse();
    }

    // ----------------------------------------------------- idempotency

    /**
     * A second run is what every restart is. Invoking the runner again is the
     * closest a test can get to that without booting a second context, and it
     * exercises the same method Boot calls.
     */
    @Test
    void runningTheBootstrapAgainCreatesNothingNew() {
        long householdsBefore = totalHouseholds();
        long membersBefore = totalMembers();
        String hashBefore = storedHash();

        bootstrap.run(new DefaultApplicationArguments());
        bootstrap.run(new DefaultApplicationArguments());

        assertThat(totalHouseholds()).isEqualTo(householdsBefore);
        assertThat(totalMembers()).isEqualTo(membersBefore);
        assertThat(provisioningService.membersOf(onlyMemberWithTheDevEmail().householdId()))
                .hasSize(1);

        assertThat(storedHash())
                .as("an idempotent run must not even re-hash, or every restart would invalidate nothing "
                        + "but would still rewrite a credential row")
                .isEqualTo(hashBefore);
    }

    // ----------------------------------------------------- login flow

    /**
     * The point of the whole exercise: the account the bootstrap made can
     * actually sign in, through the existing login path rather than a
     * special one (PRD §FR-1).
     */
    @Test
    void theBootstrappedAdminCanLogInThroughTheExistingIdentityService() {
        TokenPair tokens = identityService.login(EMAIL, PASSWORD);

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.accessTokenExpiresInSeconds()).isPositive();

        assertThat(identityService.authenticate(tokens.accessToken()))
                .satisfies(member -> {
                    assertThat(member.email()).isEqualTo(EMAIL);
                    assertThat(member.role()).isEqualTo(Role.ADMIN);
                    assertThat(member.isAdmin()).isTrue();
                });
    }

    /** Email is an identifier, so login is case-insensitive; the password is not. */
    @Test
    void theWrongPasswordIsStillRejectedForTheBootstrappedAdmin() {
        assertThat(identityService.login(EMAIL.toUpperCase(Locale.ROOT), PASSWORD).accessToken())
                .isNotBlank();

        assertThatThrownBy(() -> identityService.login(EMAIL, "not-the-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // ----------------------------------------------------------- setup

    private Member onlyMemberWithTheDevEmail() {
        List<UUID> households = jdbcTemplate.query(
                "SELECT household_id FROM member WHERE lower(email) = lower(?)",
                (rs, row) -> (UUID) rs.getObject("household_id"),
                EMAIL);

        assertThat(households)
                .as("exactly one member row holds the development address")
                .hasSize(1);

        List<Member> members = provisioningService.membersOf(households.get(0)).stream()
                .filter(member -> member.email().equalsIgnoreCase(EMAIL))
                .toList();

        assertThat(members).hasSize(1);
        return members.get(0);
    }

    private String storedHash() {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM member WHERE lower(email) = lower(?)", String.class, EMAIL);
    }

    private long totalHouseholds() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM household", Long.class);
    }

    private long totalMembers() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member", Long.class);
    }

    private long householdCount(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM household WHERE name = ?", Long.class, name);
    }
}
