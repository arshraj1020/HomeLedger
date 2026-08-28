package com.householdledger.identity;

import com.householdledger.identity.api.*;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
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
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 identity behaviour against a real PostgreSQL 16 container
 * (PRD §FR-1). Covers what unit tests cannot: bcrypt verification against a
 * persisted hash, refresh-token rotation as a database transaction, and
 * revocation surviving as stored state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class IdentityServiceIT {

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
    private DataSource dataSource;

    private static final String PASSWORD = "correct-horse-battery-staple";

    private Household household;
    private Member member;
    private String email;

    @BeforeEach
    void provision() {
        household = provisioningService.createHousehold("Test Household");
        // Unique per test method: member.email is globally UNIQUE (PRD §6.3).
        email = "papa+" + UUID.randomUUID() + "@example.com";
        member = provisioningService.registerMember(household.id(), "Papa", email, PASSWORD, Role.ADMIN);
    }

    // ---------- provisioning ----------

    @Test
    void registeredMemberIsPersistedWithABcryptHashNotThePlaintextPassword() throws Exception {
        String storedHash = readPasswordHash(member.id());

        assertThat(storedHash).isNotNull();
        assertThat(storedHash).doesNotContain(PASSWORD);
        // bcrypt hashes are identifiable by their algorithm prefix (PRD §FR-1).
        assertThat(storedHash).matches("^\\$2[aby]\\$.{56}$");
    }

    @Test
    void domainMemberCarriesNoCredentialMaterial() {
        assertThat(member.email()).isEqualTo(email);
        assertThat(member.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void duplicateEmailIsRejected() {
        assertThatThrownBy(() -> provisioningService.registerMember(
                household.id(), "Impostor", email, "another-password", Role.MEMBER))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void duplicateEmailIsRejectedRegardlessOfCase() {
        assertThatThrownBy(() -> provisioningService.registerMember(
                household.id(), "Impostor", email.toUpperCase(), "another-password", Role.MEMBER))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void registeringIntoAnUnknownHouseholdIsRejected() {
        assertThatThrownBy(() -> provisioningService.registerMember(
                UUID.randomUUID(), "Ghost", "ghost@example.com", PASSWORD, Role.MEMBER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankPasswordIsRejected() {
        assertThatThrownBy(() -> provisioningService.registerMember(
                household.id(), "NoPass", "nopass@example.com", "  ", Role.MEMBER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void membersOfListsTheHouseholdsMembers() {
        provisioningService.registerMember(
                household.id(), "Mummy", "mummy+" + UUID.randomUUID() + "@example.com", PASSWORD, Role.MEMBER);

        assertThat(provisioningService.membersOf(household.id())).hasSize(2);
    }

    // ---------- login ----------

    @Test
    void loginWithCorrectCredentialsIssuesAUsableTokenPair() {
        TokenPair pair = identityService.login(email, PASSWORD);

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        assertThat(pair.accessTokenExpiresInSeconds()).isEqualTo(900); // 15 minutes

        AuthenticatedMember principal = identityService.authenticate(pair.accessToken());
        assertThat(principal.memberId()).isEqualTo(member.id());
        assertThat(principal.householdId()).isEqualTo(household.id());
        assertThat(principal.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() {
        assertThatCode(() -> identityService.login(email.toUpperCase(), PASSWORD)).doesNotThrowAnyException();
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        assertThatThrownBy(() -> identityService.login(email, "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginWithUnknownEmailIsRejectedIdenticallyToAWrongPassword() {
        // Same exception type and message as the wrong-password case, so the
        // endpoint cannot be used to discover which addresses are registered.
        assertThatThrownBy(() -> identityService.login("nobody@example.com", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(new InvalidCredentialsException().getMessage());
    }

    @Test
    void loginWithNullCredentialsIsRejected() {
        assertThatThrownBy(() -> identityService.login(null, PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> identityService.login(email, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // ---------- refresh rotation ----------

    @Test
    void refreshIssuesANewPairAndRevokesThePresentedToken() {
        TokenPair original = identityService.login(email, PASSWORD);

        TokenPair rotated = identityService.refresh(original.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(original.refreshToken());
        assertThat(identityService.authenticate(rotated.accessToken()).memberId()).isEqualTo(member.id());

        // PRD §FR-1 "rotating": the old token must not work a second time.
        assertThatThrownBy(() -> identityService.refresh(original.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refreshTokensChainAcrossMultipleRotations() {
        TokenPair first = identityService.login(email, PASSWORD);
        TokenPair second = identityService.refresh(first.refreshToken());
        TokenPair third = identityService.refresh(second.refreshToken());

        assertThat(third.refreshToken())
                .isNotEqualTo(second.refreshToken())
                .isNotEqualTo(first.refreshToken());
        assertThatThrownBy(() -> identityService.refresh(second.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refreshWithAnUnknownTokenIsRejected() {
        assertThatThrownBy(() -> identityService.refresh("not-a-real-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refreshWithNullOrBlankTokenIsRejected() {
        assertThatThrownBy(() -> identityService.refresh(null))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> identityService.refresh("   "))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void anExpiredRefreshTokenIsRejected() throws Exception {
        TokenPair pair = identityService.login(email, PASSWORD);
        // Backdate expiry directly in the database rather than waiting 7 days.
        expireAllRefreshTokensFor(member.id());

        assertThatThrownBy(() -> identityService.refresh(pair.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // ---------- logout ----------

    @Test
    void logoutRevokesTheRefreshToken() {
        TokenPair pair = identityService.login(email, PASSWORD);

        identityService.logout(pair.refreshToken());

        assertThatThrownBy(() -> identityService.refresh(pair.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logoutIsIdempotent() {
        TokenPair pair = identityService.login(email, PASSWORD);
        identityService.logout(pair.refreshToken());

        // Second logout, unknown token, and null all succeed silently.
        assertThatCode(() -> identityService.logout(pair.refreshToken())).doesNotThrowAnyException();
        assertThatCode(() -> identityService.logout("never-existed")).doesNotThrowAnyException();
        assertThatCode(() -> identityService.logout(null)).doesNotThrowAnyException();
    }

    @Test
    void logoutRevokesOnlyThePresentedTokenNotEverySession() {
        // Two independent logins model two devices; signing out on one must
        // not sign the other out (PRD §FR-1 requires revocable tokens, not
        // global sign-out).
        TokenPair phone = identityService.login(email, PASSWORD);
        TokenPair laptop = identityService.login(email, PASSWORD);

        identityService.logout(phone.refreshToken());

        assertThatCode(() -> identityService.refresh(laptop.refreshToken())).doesNotThrowAnyException();
    }

    @Test
    void revocationIsRecordedRatherThanDeletingTheRow() throws Exception {
        TokenPair pair = identityService.login(email, PASSWORD);
        int before = countRefreshTokens(member.id());

        identityService.logout(pair.refreshToken());

        // Append-only stance (PRD §3.5): the row survives, marked revoked.
        assertThat(countRefreshTokens(member.id())).isEqualTo(before);
        assertThat(countRevokedRefreshTokens(member.id())).isEqualTo(1);
    }

    // ---------- helpers ----------

    private String readPasswordHash(UUID memberId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT password_hash FROM member WHERE id = ?")) {
            ps.setObject(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void expireAllRefreshTokensFor(UUID memberId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE refresh_token SET expires_at = now() - interval '1 day' WHERE member_id = ?")) {
            ps.setObject(1, memberId);
            ps.executeUpdate();
        }
    }

    private int countRefreshTokens(UUID memberId) throws Exception {
        return countQuery("SELECT COUNT(*) FROM refresh_token WHERE member_id = ?", memberId);
    }

    private int countRevokedRefreshTokens(UUID memberId) throws Exception {
        return countQuery("SELECT COUNT(*) FROM refresh_token WHERE member_id = ? AND revoked_at IS NOT NULL", memberId);
    }

    private int countQuery(String sql, UUID memberId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
