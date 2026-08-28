package com.householdledger.ledger;

import com.householdledger.ledger.api.*;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.Transaction;
import com.householdledger.ledger.domain.UnbalancedTransactionException;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end proof that {@link LedgerService}, running on real Spring Data
 * JPA / Hibernate, persists transactions correctly against a real Postgres
 * 16 container — specifically exercising the one-row-at-a-time posting
 * insert pattern described in {@code LedgerServiceImpl}'s Javadoc, which is
 * the PRD's highest-risk item (§10). This is the "Hibernate/JPA flush
 * behavior" proof requested alongside the raw-JDBC {@code InvariantTriggerIT}
 * (which proves the trigger works in isolation, bypassing the app entirely).
 *
 * <p>Account management (creating accounts through the API) is Phase 3 —
 * not built yet — so this test seeds household/member/account rows directly
 * via JDBC, exactly as {@code InvariantTriggerIT} does, and only then drives
 * everything else through the real {@link LedgerService}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LedgerServicePersistenceIT {

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

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private DataSource dataSource;

    private UUID householdId;
    private UUID memberId;
    private UUID cashAccountId;
    private UUID groceriesAccountId;
    private UUID inactiveAccountId;

    @BeforeEach
    void seedFixtures() throws Exception {
        householdId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        cashAccountId = UUID.randomUUID();
        groceriesAccountId = UUID.randomUUID();
        inactiveAccountId = UUID.randomUUID();

        try (Connection c = dataSource.getConnection()) {
            insert(c, "INSERT INTO household (id, name, currency) VALUES (?, 'Test Household', 'INR')", householdId);
            insertMember(c);
            insertAccount(c, cashAccountId, "ASSET", "Cash", true);
            insertAccount(c, groceriesAccountId, "EXPENSE", "Groceries", true);
            insertAccount(c, inactiveAccountId, "ASSET", "Closed Account", false);
        }
    }

    @Test
    void recordTransactionPersistsThroughHibernateAndBalancesAreDerivedCorrectly() {
        Transaction txn = ledgerService.recordTransaction(householdId, LocalDate.now(), "Weekly groceries", memberId,
                List.of(new PostingLine(groceriesAccountId, 420_000), new PostingLine(cashAccountId, -420_000)));

        assertThat(txn.postings()).hasSize(2);

        // Balances are derived (PRD §3.4), not stored — this reads through
        // Hibernate/JPA back to Postgres and sums the just-persisted rows.
        assertThat(ledgerService.accountBalanceMinor(householdId, groceriesAccountId, null)).isEqualTo(420_000);
        assertThat(ledgerService.accountBalanceMinor(householdId, cashAccountId, null)).isEqualTo(-420_000);
    }

    @Test
    void recordTransactionRejectsUnbalancedPostingsBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> ledgerService.recordTransaction(householdId, LocalDate.now(), "Bad", memberId,
                List.of(new PostingLine(groceriesAccountId, 420_000), new PostingLine(cashAccountId, -410_000))))
                .isInstanceOf(UnbalancedTransactionException.class);

        // Nothing should have been written.
        assertThat(ledgerService.accountBalanceMinor(householdId, groceriesAccountId, null)).isZero();
    }

    @Test
    void recordTransactionRejectsAccountFromAnotherHousehold() {
        UUID otherHouseholdAccountId = UUID.randomUUID();
        assertThatThrownBy(() -> ledgerService.recordTransaction(householdId, LocalDate.now(), "Bad", memberId,
                List.of(new PostingLine(otherHouseholdAccountId, 100), new PostingLine(cashAccountId, -100))))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void recordTransactionRejectsDeactivatedAccount() {
        assertThatThrownBy(() -> ledgerService.recordTransaction(householdId, LocalDate.now(), "Bad", memberId,
                List.of(new PostingLine(inactiveAccountId, 100), new PostingLine(cashAccountId, -100))))
                .isInstanceOf(InactiveAccountException.class);
    }

    @Test
    void reverseTransactionProducesExactInverseAndUpdatesBalances() {
        Transaction original = ledgerService.recordTransaction(householdId, LocalDate.now(), "Weekly groceries", memberId,
                List.of(new PostingLine(groceriesAccountId, 420_000), new PostingLine(cashAccountId, -420_000)));

        Transaction reversal = ledgerService.reverseTransaction(householdId, original.id(), memberId);

        assertThat(reversal.reversesTransactionId()).isEqualTo(original.id());
        assertThat(ledgerService.accountBalanceMinor(householdId, groceriesAccountId, null)).isZero();
        assertThat(ledgerService.accountBalanceMinor(householdId, cashAccountId, null)).isZero();
    }

    @Test
    void reversingAnAlreadyReversedTransactionThrows() {
        Transaction original = ledgerService.recordTransaction(householdId, LocalDate.now(), "Weekly groceries", memberId,
                List.of(new PostingLine(groceriesAccountId, 100), new PostingLine(cashAccountId, -100)));
        ledgerService.reverseTransaction(householdId, original.id(), memberId);

        assertThatThrownBy(() -> ledgerService.reverseTransaction(householdId, original.id(), memberId))
                .isInstanceOf(TransactionAlreadyReversedException.class);
    }

    @Test
    void reversingAReversalThrows() {
        Transaction original = ledgerService.recordTransaction(householdId, LocalDate.now(), "Weekly groceries", memberId,
                List.of(new PostingLine(groceriesAccountId, 100), new PostingLine(cashAccountId, -100)));
        Transaction reversal = ledgerService.reverseTransaction(householdId, original.id(), memberId);

        assertThatThrownBy(() -> ledgerService.reverseTransaction(householdId, reversal.id(), memberId))
                .isInstanceOf(ReversalTransactionCannotBeReversedException.class);
    }

    @Test
    void getAccountFromAnotherHouseholdThrowsNotFound() {
        assertThatThrownBy(() -> ledgerService.getAccount(UUID.randomUUID(), cashAccountId))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getAccountReturnsFramelessDomainSnapshot() {
        Account account = ledgerService.getAccount(householdId, cashAccountId);
        assertThat(account.id()).isEqualTo(cashAccountId);
        assertThat(account.active()).isTrue();
    }

    private void insert(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    private void insertMember(Connection c) throws Exception {
        insert(c, "INSERT INTO member (id, household_id, name, email, password_hash, role) VALUES (?, ?, 'Tester', ?, 'hash', 'ADMIN')",
                memberId, householdId, "tester+" + memberId + "@example.com");
    }

    private void insertAccount(Connection c, UUID id, String type, String name, boolean active) throws Exception {
        insert(c, "INSERT INTO account (id, household_id, type, name, is_active) VALUES (?, ?, ?, ?, ?)",
                id, householdId, type, name, active);
    }
}
