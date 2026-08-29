package com.householdledger.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the first household admin, used only under the
 * {@code dev} profile.
 *
 * <p><b>There are no default values for the email or the password, and there
 * must never be.</b> A default credential in a repository is a credential
 * everyone has: it ends up in a container image, then on a machine that was
 * only ever meant to be a demo, and the account it opens is an ADMIN of a
 * household. So {@link #validate()} refuses to start instead — mirroring
 * {@link JwtProperties#validate()}, which takes the same position about the
 * signing key (PRD §5: "No plaintext secrets in repo").
 *
 * <p>The household and member names do have defaults, because they are
 * labels rather than secrets and nothing is gained by making a developer
 * invent them.
 */
@ConfigurationProperties(prefix = "bootstrap.dev-admin")
public class DevelopmentAdminProperties {

    private String householdName = "Development Household";
    private String memberName = "Development Admin";
    private String email;
    private String password;

    public String getHouseholdName() {
        return householdName;
    }

    public void setHouseholdName(String householdName) {
        this.householdName = householdName;
    }

    public String getMemberName() {
        return memberName;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** The login identifier, trimmed. Email is an identifier, and a stray space in an env var should not create a second account. */
    public String normalisedEmail() {
        return email == null ? null : email.trim();
    }

    /**
     * @throws IllegalStateException with a message naming the environment
     *         variable to set. Called from the bootstrap's constructor, so an
     *         incompletely configured {@code dev} profile fails at startup
     *         rather than at the moment the first person tries to log in.
     */
    public void validate() {
        if (householdName == null || householdName.isBlank()) {
            throw new IllegalStateException(
                    "bootstrap.dev-admin.household-name must not be blank. Unset DEV_HOUSEHOLD_NAME to use the default.");
        }
        if (memberName == null || memberName.isBlank()) {
            throw new IllegalStateException(
                    "bootstrap.dev-admin.member-name must not be blank. Unset DEV_ADMIN_NAME to use the default.");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalStateException(
                    "bootstrap.dev-admin.email is not set, so the 'dev' profile has no address to create the "
                            + "first household admin with. Set the DEV_ADMIN_EMAIL environment variable, "
                            + "or start without the 'dev' profile.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "bootstrap.dev-admin.password is not set. There is deliberately no default: a password "
                            + "committed to the repository would be a known password for an ADMIN account. "
                            + "Set the DEV_ADMIN_PASSWORD environment variable, "
                            + "or start without the 'dev' profile.");
        }
    }
}
