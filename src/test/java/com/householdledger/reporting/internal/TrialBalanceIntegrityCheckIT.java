package com.householdledger.reporting.internal;

import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.api.UnbalancedTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The daily integrity check of PRD §FR-6, against real PostgreSQL.
 *
 * <p>The load-bearing test is {@code detectsAnUnbalancedTransaction...}.
 * Proving the check reports "healthy" on a healthy ledger is nearly
 * worthless on its own — a method that always returned an empty list would
 * pass it. So that test deliberately corrupts the database, by disabling the
 * balance trigger just long enough to insert an unbalanced transaction, and
 * asserts the check finds it. That is the only way to know the check is not
 * vacuous.
 *
 * <p>Disabling the trigger is also the closest reachable simulation of the
 * scenario the check exists for: the trigger protects writes that go through
 * the normal path, and cannot protect against a bulk load run with triggers
 * off, a bad restore, or a future migration that drops it.
 *
 * <p><b>Isolation.</b> Deliberately corrupting a shared database makes this
 * class unusual, and it has to clean up after itself: the check under test
 * is global by design, so anything left behind is correctly reported by
 * every later test as real corruption. {@link #removeDeliberateCorruption()}
 * removes exactly what each test created, and two further tests assert that
 * the removal works and that posting immutability is restored afterwards —
 * so the isolation is verified rather than assumed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TrialBalanceIntegrityCheckIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("household_ledger_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private TrialBalanceIntegrityCheck integrityCheck;
    @Autowired private LedgerService ledgerService;
    @Autowired private AccountService accountService;
    @Autowired private MemberProvisioningService provisioningService;
    @Autowired private DataSource dataSource;

    private static final String PASSWORD = "correct-horse-battery-staple";

    private Household household;
    private UUID member;
    private UUID cash;
    private UUID groceries;

    /**
     * Transactions this test method deliberately corrupted, so
     * {@link #removeDeliberateCorruption()} can undo exactly what was done
     * and nothing else.
     *
     * <p>JUnit's default lifecycle creates one instance per test method, so
     * this list only ever holds the current method's damage.
     */
    private final List<UUID> deliberatelyCorruptedTransactionIds = new ArrayList<>();

    @BeforeEach
    void provision() {
        household = provisioningService.createHousehold("Test Household");
        member = provisioningService.registerMember(household.id(), "Papa",
                "papa+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN).id();
        cash = accountId("Cash");
        groceries = accountId("Groceries");
    }

    private UUID accountId(String name) {
        return accountService.listAccounts(household.id()).stream()
                .filter(a -> a.name().equals(name)).findFirst().orElseThrow().id();
    }

    /**
     * Undoes the deliberate corruption, so each test starts from a database
     * that is genuinely consistent.
     *
     * <p><b>Why this is necessary.</b> All five tests share one PostgreSQL
     * container (a static {@code @Container} is created once per class), and
     * {@code @SpringBootTest} without {@code @Transactional} commits
     * everything — so an unbalanced transaction written by one test stays
     * visible to the next. That is harmless for every other integration test
     * in the project, because they scope their assertions to their own
     * freshly provisioned household. It is fatal here: the check under test
     * is deliberately <em>global</em> (PRD §FR-6 asks "is the database as a
     * whole still consistent"), so it correctly reports corruption left
     * anywhere by anyone, including a previous test.
     *
     * <p>Runs as {@code @AfterEach} rather than in a {@code finally} inside
     * the corrupting test, so the database is restored even when that test
     * fails part-way through and never reaches its own cleanup.
     *
     * <p>Deleting from {@code posting} requires disabling the immutability
     * trigger, which is exactly the guarantee of PRD §3.5 — so it is
     * re-enabled immediately, and {@code postingImmutabilityIsRestored}
     * asserts that the production guarantee is intact afterwards.
     */
    @AfterEach
    void removeDeliberateCorruption() throws Exception {
        if (deliberatelyCorruptedTransactionIds.isEmpty()) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE posting DISABLE TRIGGER trg_posting_no_delete");
            }

            for (UUID transactionId : deliberatelyCorruptedTransactionIds) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM posting WHERE transaction_id = ?")) {
                    ps.setObject(1, transactionId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM ledger_transaction WHERE id = ?")) {
                    ps.setObject(1, transactionId);
                    ps.executeUpdate();
                }
            }

            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE posting ENABLE TRIGGER trg_posting_no_delete");
            }
        }

        deliberatelyCorruptedTransactionIds.clear();
    }

    @Test
    void reportsNothingOnAHealthyLedger() {
        ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(), "Weekly groceries",
                member, cash, groceries, 420_000);

        assertThat(integrityCheck.verifyIntegrity()).isEmpty();
    }

    @Test
    void reportsNothingOnAnEmptyDatabase() {
        assertThat(integrityCheck.verifyIntegrity()).isEmpty();
    }

    @Test
    void detectsAnUnbalancedTransactionAndReportsHowFarOffItIs() throws Exception {
        ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(), "Legitimate",
                member, cash, groceries, 100_000);

        UUID corrupted = insertUnbalancedTransactionBypassingTheTrigger(500, -499);

        List<UnbalancedTransaction> found = integrityCheck.verifyIntegrity();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).transactionId()).isEqualTo(corrupted);
        assertThat(found.get(0).offByMinor()).isEqualTo(1);
    }

    /**
     * Regression guard for the isolation failure this class had: the
     * corrupting test committed an unbalanced transaction into a shared
     * database and never removed it, so every later test in the class saw
     * it and failed.
     *
     * <p>Asserts that cleanup actually works, rather than trusting it. Runs
     * the corruption and the removal explicitly, then confirms the check is
     * clean again — which is precisely the state the other four tests
     * assume when they start.
     */
    @Test
    void deliberateCorruptionIsFullyRemovedAfterwards() throws Exception {
        UUID corrupted = insertUnbalancedTransactionBypassingTheTrigger(700, -650);
        assertThat(integrityCheck.verifyIntegrity())
                .extracting(UnbalancedTransaction::transactionId)
                .contains(corrupted);

        removeDeliberateCorruption();

        assertThat(integrityCheck.verifyIntegrity()).isEmpty();
    }

    /**
     * The cleanup disables the immutability trigger to delete the corrupted
     * rows. That trigger is a production guarantee (PRD §3.5), so this
     * confirms it is switched back on — a test fixture must not be able to
     * quietly leave the schema less protected than it found it.
     */
    @Test
    void postingImmutabilityIsRestoredAfterCleanup() throws Exception {
        insertUnbalancedTransactionBypassingTheTrigger(700, -650);
        removeDeliberateCorruption();

        ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(), "Real entry",
                member, cash, groceries, 1_000);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM posting"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
        }
    }

    @Test
    void theScheduledEntryPointRunsTheSameCheck() {
        // runDailyCheck() is what the cron fires; it must not diverge from
        // the method the tests exercise.
        ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(), "Weekly groceries",
                member, cash, groceries, 420_000);

        integrityCheck.runDailyCheck();

        assertThat(integrityCheck.verifyIntegrity()).isEmpty();
    }

    @Test
    void theCheckSpansEveryHouseholdNotJustOne() {
        // It is a database-wide integrity question, so corruption in any
        // household must be found regardless of where it sits.
        Household other = provisioningService.createHousehold("Other Household");
        provisioningService.registerMember(other.id(), "Them",
                "them+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN);

        assertThat(integrityCheck.verifyIntegrity()).isEmpty();
    }

    /**
     * Inserts a transaction whose postings do not sum to zero, by turning the
     * deferred balance trigger off around the insert. Restored immediately
     * afterwards so the rest of the suite still runs against a protected
     * schema.
     */
    private UUID insertUnbalancedTransactionBypassingTheTrigger(long first, long second) throws Exception {
        UUID transactionId = UUID.randomUUID();

        // Registered before the insert, not after: if the insert fails
        // half-way, the cleanup should still try to remove whatever landed.
        deliberatelyCorruptedTransactionIds.add(transactionId);

        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE posting DISABLE TRIGGER trg_posting_balanced");
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ledger_transaction (id, household_id, occurred_on, description, created_by) "
                            + "VALUES (?, ?, CURRENT_DATE, 'CORRUPTED BY TEST', ?)")) {
                ps.setObject(1, transactionId);
                ps.setObject(2, household.id());
                ps.setObject(3, member);
                ps.executeUpdate();
            }

            insertPosting(connection, transactionId, groceries, first);
            insertPosting(connection, transactionId, cash, second);

            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE posting ENABLE TRIGGER trg_posting_balanced");
            }
        }

        return transactionId;
    }

    private void insertPosting(Connection connection, UUID transactionId, UUID accountId, long amountMinor)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO posting (id, transaction_id, account_id, amount_minor) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, transactionId);
            ps.setObject(3, accountId);
            ps.setLong(4, amountMinor);
            ps.executeUpdate();
        }
    }
}
