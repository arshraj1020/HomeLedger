package com.householdledger.identity;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.identity.api.IdentityService;
import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.api.TokenPair;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountNotFoundException;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.api.PostingLine;
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
 * PRD §9 acceptance criterion: "A member of one household receives 404 (not
 * 403 — no existence leak) for another household's resources", and PRD
 * §FR-1: "Cross-household access must be impossible even with a forged ID in
 * the path."
 *
 * <p>Two households are fully provisioned, each with its own member and
 * accounts, and every attempt by household A to touch household B's data is
 * asserted to fail as *not found* rather than *forbidden*. The distinction
 * matters: a 403 confirms the resource exists, which is precisely the leak
 * the PRD forbids.
 *
 * <p>{@link AccountNotFoundException} is the ledger module's not-found
 * signal and maps to HTTP 404 (see its Javadoc); asserting on it here keeps
 * the test meaningful before the account/transaction controllers land in
 * Phases 3 and 4.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class HouseholdScopingIT {

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
    private IdentityService identityService;

    @Autowired
    private MemberProvisioningService provisioningService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private DataSource dataSource;

    private static final String PASSWORD = "correct-horse-battery-staple";

    private Household householdA;
    private Household householdB;
    private Member memberA;
    private String emailA;

    private UUID cashA;
    private UUID groceriesA;
    private UUID cashB;
    private UUID groceriesB;

    @BeforeEach
    void provisionTwoHouseholds() throws Exception {
        householdA = provisioningService.createHousehold("Household A");
        householdB = provisioningService.createHousehold("Household B");

        emailA = "a+" + UUID.randomUUID() + "@example.com";
        memberA = provisioningService.registerMember(householdA.id(), "Member A", emailA, PASSWORD, Role.ADMIN);
        provisioningService.registerMember(
                householdB.id(), "Member B", "b+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.ADMIN);

        // Accounts are Phase 3; seed directly, as the Phase 1 ITs do.
        cashA = insertAccount(householdA.id(), "ASSET", "Cash");
        groceriesA = insertAccount(householdA.id(), "EXPENSE", "Groceries");
        cashB = insertAccount(householdB.id(), "ASSET", "Cash");
        groceriesB = insertAccount(householdB.id(), "EXPENSE", "Groceries");
    }

    // ---------- the token is the only source of household identity ----------

    @Test
    void theAccessTokenCarriesTheMembersOwnHouseholdId() {
        TokenPair pair = identityService.login(emailA, PASSWORD);
        AuthenticatedMember principal = identityService.authenticate(pair.accessToken());

        assertThat(principal.householdId()).isEqualTo(householdA.id());
        assertThat(principal.householdId()).isNotEqualTo(householdB.id());
        assertThat(principal.memberId()).isEqualTo(memberA.id());
    }

    // ---------- reads ----------

    @Test
    void readingAnotherHouseholdsAccountIsNotFoundRatherThanForbidden() {
        UUID scopedHousehold = authenticatedHouseholdOfA();

        assertThatThrownBy(() -> ledgerService.getAccount(scopedHousehold, cashB))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void readingAnotherHouseholdsAccountBalanceIsNotFound() {
        UUID scopedHousehold = authenticatedHouseholdOfA();

        assertThatThrownBy(() -> ledgerService.accountBalanceMinor(scopedHousehold, groceriesB, null))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void aForgedHouseholdIdInTheRequestDoesNotGrantAccess() {
        // Simulates a caller putting household B's id in a path parameter.
        // The service is always called with the household id from the
        // verified token, never the supplied one, so B's account stays
        // unreachable — the forged id simply never reaches the query.
        UUID scopedHousehold = authenticatedHouseholdOfA();
        UUID forged = householdB.id();

        assertThat(scopedHousehold).isNotEqualTo(forged);
        assertThatThrownBy(() -> ledgerService.getAccount(scopedHousehold, cashB))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void memberAStillSeesItsOwnAccountsNormally() {
        UUID scopedHousehold = authenticatedHouseholdOfA();

        assertThat(ledgerService.getAccount(scopedHousehold, cashA).id()).isEqualTo(cashA);
        assertThat(ledgerService.accountBalanceMinor(scopedHousehold, cashA, null)).isZero();
    }

    // ---------- writes ----------

    @Test
    void recordingATransactionAgainstAnotherHouseholdsAccountsIsRejected() {
        UUID scopedHousehold = authenticatedHouseholdOfA();

        assertThatThrownBy(() -> ledgerService.recordTransaction(
                scopedHousehold, LocalDate.now(), "Cross-household attempt", memberA.id(),
                List.of(new PostingLine(groceriesB, 10_000), new PostingLine(cashB, -10_000))))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void aTransactionMixingTwoHouseholdsAccountsIsRejected() {
        // The subtler attack: one leg legitimately owned, one leg stolen.
        UUID scopedHousehold = authenticatedHouseholdOfA();

        assertThatThrownBy(() -> ledgerService.recordTransaction(
                scopedHousehold, LocalDate.now(), "Mixed households", memberA.id(),
                List.of(new PostingLine(groceriesA, 10_000), new PostingLine(cashB, -10_000))))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void nothingIsWrittenWhenACrossHouseholdTransactionIsRejected() {
        UUID scopedHousehold = authenticatedHouseholdOfA();

        assertThatThrownBy(() -> ledgerService.recordTransaction(
                scopedHousehold, LocalDate.now(), "Mixed households", memberA.id(),
                List.of(new PostingLine(groceriesA, 10_000), new PostingLine(cashB, -10_000))))
                .isInstanceOf(AccountNotFoundException.class);

        // Household B's balance is untouched, and so is A's own account.
        assertThat(ledgerService.accountBalanceMinor(householdB.id(), cashB, null)).isZero();
        assertThat(ledgerService.accountBalanceMinor(scopedHousehold, groceriesA, null)).isZero();
    }

    @Test
    void reversingAnotherHouseholdsTransactionIsNotFound() {
        // Household B records a legitimate transaction; A must not be able to
        // reverse it even knowing its id.
        var transactionB = ledgerService.recordTransaction(
                householdB.id(), LocalDate.now(), "B's groceries",
                provisioningService.membersOf(householdB.id()).get(0).id(),
                List.of(new PostingLine(groceriesB, 42_000), new PostingLine(cashB, -42_000)));

        UUID scopedHousehold = authenticatedHouseholdOfA();

        assertThatThrownBy(() -> ledgerService.reverseTransaction(
                scopedHousehold, transactionB.id(), memberA.id()))
                .isInstanceOf(com.householdledger.ledger.api.TransactionNotFoundException.class);

        // And B's transaction is still intact and un-reversed.
        assertThat(ledgerService.accountBalanceMinor(householdB.id(), groceriesB, null)).isEqualTo(42_000);
    }

    // ---------- helpers ----------

    /**
     * Logs member A in and returns the household id from the verified token —
     * deliberately routed through the real login/authenticate path rather
     * than using {@code householdA.id()} directly, so the test proves the
     * scoping value actually originates from the token.
     */
    private UUID authenticatedHouseholdOfA() {
        TokenPair pair = identityService.login(emailA, PASSWORD);
        return identityService.authenticate(pair.accessToken()).householdId();
    }

    private UUID insertAccount(UUID householdId, String type, String name) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO account (id, household_id, type, name, is_active) VALUES (?, ?, ?, ?, TRUE)")) {
            ps.setObject(1, id);
            ps.setObject(2, householdId);
            ps.setString(3, type);
            ps.setString(4, name);
            ps.executeUpdate();
        }
        return id;
    }
}
