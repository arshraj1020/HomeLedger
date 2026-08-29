package com.householdledger.identity.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refusal to invent a credential.
 *
 * <p>These are the tests that keep a default password out of the repository.
 * The easy "fix" for a developer hitting the startup failure is to give
 * {@code email} and {@code password} defaults in this class; these tests
 * fail if anyone does.
 */
class DevelopmentAdminPropertiesTest {

    private static DevelopmentAdminProperties complete() {
        DevelopmentAdminProperties properties = new DevelopmentAdminProperties();
        properties.setEmail("dev@example.com");
        properties.setPassword("a-password-only-this-developer-knows");
        return properties;
    }

    @Test
    void aFreshInstanceHasNoCredentialsAtAll() {
        DevelopmentAdminProperties properties = new DevelopmentAdminProperties();

        assertThat(properties.getEmail())
                .as("a default email would be a shared identity across every checkout")
                .isNull();
        assertThat(properties.getPassword())
                .as("a default password would be a known password for an ADMIN account")
                .isNull();
    }

    @Test
    void namesHaveDefaultsBecauseTheyAreLabelsNotSecrets() {
        DevelopmentAdminProperties properties = new DevelopmentAdminProperties();

        assertThat(properties.getHouseholdName()).isNotBlank();
        assertThat(properties.getMemberName()).isNotBlank();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void aMissingEmailStopsStartupAndNamesTheVariableToSet(String email) {
        DevelopmentAdminProperties properties = complete();
        properties.setEmail(email);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV_ADMIN_EMAIL");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void aMissingPasswordStopsStartupAndNamesTheVariableToSet(String password) {
        DevelopmentAdminProperties properties = complete();
        properties.setPassword(password);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV_ADMIN_PASSWORD");
    }

    @Test
    void aBlankHouseholdOrMemberNameIsAlsoRefused() {
        DevelopmentAdminProperties blankHousehold = complete();
        blankHousehold.setHouseholdName("  ");
        assertThatThrownBy(blankHousehold::validate).isInstanceOf(IllegalStateException.class);

        DevelopmentAdminProperties blankMember = complete();
        blankMember.setMemberName("  ");
        assertThatThrownBy(blankMember::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFullySuppliedConfigurationValidates() {
        assertThatCode(complete()::validate).doesNotThrowAnyException();
    }

    /** A stray space in an environment variable should not create a second account. */
    @Test
    void theEmailIsTrimmedBecauseItIsAnIdentifier() {
        DevelopmentAdminProperties properties = complete();
        properties.setEmail("  dev@example.com  ");

        assertThat(properties.normalisedEmail()).isEqualTo("dev@example.com");
    }

    /** No message may repeat the password back; startup logs and stack traces are files. */
    @Test
    void noFailureMessageEchoesTheSuppliedPassword() {
        DevelopmentAdminProperties properties = complete();
        properties.setPassword("super-secret-value");
        properties.setEmail("");

        assertThatThrownBy(properties::validate)
                .hasMessageNotContaining("super-secret-value");
    }
}
