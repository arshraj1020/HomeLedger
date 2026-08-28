package com.householdledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prototypes the single highest-risk item in the PRD (§10): whether the
 * deferred constraint trigger behaves correctly given Postgres's insert
 * ordering. This test talks to raw JDBC only — deliberately, since Phase 0
 * has no JPA entities yet and the whole point is to validate the trigger
 * mechanism itself, in isolation, before any application code is built on
 * top of it (per §8: "Do not move past [Phase 1] until ... the
 * database-level rejection test ... pass[es]" — this is the same test one
 * layer earlier, run against the raw schema).
 *
 * Runs against a real Postgres 16 container via Testcontainers, per PRD
 * §6.2: "the invariant cannot be tested against H2."
 */
@Testcontainers
class InvariantTriggerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("household_ledger_test")
            .withUsername("test")
            .withPassword("test");

    private Connection conn;
    private UUID householdId;
    private UUID memberId;
    private UUID assetAccountId;
    private UUID expenseAccountId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        conn.setAutoCommit(false);
        runMigrationsOnce();
        seedFixtures();
    }

    /**
     * Applies V1__baseline_schema.sql directly via JDBC (rather than pulling
     * in Flyway/Spring context) so this test stays a minimal, fast prototype
     * of the trigger mechanism alone. Only runs the migration once per
     * container by checking whether the schema already exists.
     */
    private void runMigrationsOnce() throws Exception {
        try (Statement check = conn.createStatement()) {
            ResultSet rs = check.executeQuery(
                    "SELECT to_regclass('public.posting') IS NOT NULL AS exists");
            rs.next();
            if (rs.getBoolean("exists")) {
                conn.commit();
                return;
            }
        }
        String sql = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/resources/db/migration/V1__baseline_schema.sql")));
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
        conn.commit();
    }

    private void seedFixtures() throws Exception {
        householdId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        assetAccountId = UUID.randomUUID();
        expenseAccountId = UUID.randomUUID();

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO household (id, name, currency) VALUES (?, 'Test Household', 'INR')")) {
            ps.setObject(1, householdId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO member (id, household_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Tester', ?, 'hash', 'ADMIN')")) {
            ps.setObject(1, memberId);
            ps.setObject(2, householdId);
            ps.setString(3, "tester+" + memberId + "@example.com");
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO account (id, household_id, type, name) VALUES (?, ?, 'ASSET', 'Cash')")) {
            ps.setObject(1, assetAccountId);
            ps.setObject(2, householdId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO account (id, household_id, type, name) VALUES (?, ?, 'EXPENSE', 'Groceries')")) {
            ps.setObject(1, expenseAccountId);
            ps.setObject(2, householdId);
            ps.executeUpdate();
        }
        conn.commit();
    }

    private UUID insertTransaction() throws Exception {
        UUID txnId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ledger_transaction (id, household_id, occurred_on, description, created_by) " +
                        "VALUES (?, ?, CURRENT_DATE, 'Test txn', ?)")) {
            ps.setObject(1, txnId);
            ps.setObject(2, householdId);
            ps.setObject(3, memberId);
            ps.executeUpdate();
        }
        return txnId;
    }

    private void insertPosting(UUID txnId, UUID accountId, long amountMinor) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO posting (id, transaction_id, account_id, amount_minor) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, txnId);
            ps.setObject(3, accountId);
            ps.setLong(4, amountMinor);
            ps.executeUpdate();
        }
    }

    @Test
    void balancedTwoPostingTransactionCommitsSuccessfully() throws Exception {
        UUID txnId = insertTransaction();
        // Inserted one row at a time, exactly as Hibernate would during a
        // flush — this is the scenario an IMMEDIATE trigger cannot survive.
        insertPosting(txnId, expenseAccountId, 42_000);
        insertPosting(txnId, assetAccountId, -42_000);

        // The trigger is deferred to commit, so both inserts above succeed
        // individually; the balance check only runs here.
        conn.commit();

        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) AS c FROM posting WHERE transaction_id = '" + txnId + "'");
            rs.next();
            assertThat(rs.getInt("c")).isEqualTo(2);
        }
    }

    @Test
    void unbalancedTransactionIsRejectedAtCommit() throws Exception {
        UUID txnId = insertTransaction();
        insertPosting(txnId, expenseAccountId, 42_000);
        insertPosting(txnId, assetAccountId, -41_000); // off by 1000 minor units

        assertThatThrownBy(conn::commit)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    void singlePostingTransactionIsRejectedAtCommit() throws Exception {
        UUID txnId = insertTransaction();
        insertPosting(txnId, expenseAccountId, 42_000);

        assertThatThrownBy(conn::commit)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fewer than two postings");
    }

    @Test
    void updateOnPostingIsRejected() throws Exception {
        UUID txnId = insertTransaction();
        insertPosting(txnId, expenseAccountId, 42_000);
        insertPosting(txnId, assetAccountId, -42_000);
        conn.commit();

        try (Statement st = conn.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate(
                    "UPDATE posting SET amount_minor = 1 WHERE transaction_id = '" + txnId + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
        }
        conn.rollback();
    }

    @Test
    void deleteOnPostingIsRejected() throws Exception {
        UUID txnId = insertTransaction();
        insertPosting(txnId, expenseAccountId, 42_000);
        insertPosting(txnId, assetAccountId, -42_000);
        conn.commit();

        try (Statement st = conn.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate(
                    "DELETE FROM posting WHERE transaction_id = '" + txnId + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
        }
        conn.rollback();
    }
}
