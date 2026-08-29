package com.householdledger.identity.internal;

import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first household and its ADMIN member on startup, under the
 * {@code dev} profile only.
 *
 * <p><b>Why this exists.</b> PRD §6.4's API surface has no registration
 * endpoint, and PRD §FR-1 gives members no way to create themselves — an
 * ADMIN manages members. That is the right design for a household ledger, but
 * it leaves a working install with a login page and nobody able to use it. A
 * profile-gated bootstrap solves that without putting a self-registration
 * endpoint into the application, which would be a permanent hole opened to
 * fix a first-run problem.
 *
 * <p><b>Why it is safe.</b> Three independent things have to be true before a
 * single row is written: the {@code dev} profile must be active (so the bean
 * does not exist at all otherwise — see {@link Profile}), and both
 * {@code DEV_ADMIN_EMAIL} and {@code DEV_ADMIN_PASSWORD} must be supplied, or
 * the context refuses to start. Nothing here is enabled by default, and there
 * is no credential in the repository to leak.
 *
 * <p><b>It reuses the existing provisioning path in full.</b>
 * {@link MemberProvisioningService#registerMember} is what hashes the password
 * with the application's own {@code PasswordEncoder} (bcrypt, PRD §FR-1),
 * refuses a duplicate address, and seeds the household's chart of accounts
 * (PRD §FR-2). This class writes no SQL, touches no entity, and knows nothing
 * about how a password is stored — so a change to hashing or seeding applies
 * here automatically, and cannot be forgotten.
 *
 * <p><b>Idempotency</b> keys on the member's email, which the schema already
 * makes unique. If the address exists the runner returns without writing, so
 * restarting the application is a no-op. Household creation and member
 * registration share one transaction, so a failure half way through cannot
 * leave an orphan household for the next start to duplicate.
 *
 * <p><b>The password is never logged.</b> The email is, because it is the
 * login identifier and a developer needs to know which address was created;
 * the password is the one the developer set, and a log line is a file, a
 * scrollback buffer and a shipped container's stdout. Nothing else in this
 * codebase logs a credential, and this is not the place to start.
 */
@Component
@Profile(DevelopmentAdminBootstrap.PROFILE)
@EnableConfigurationProperties(DevelopmentAdminProperties.class)
class DevelopmentAdminBootstrap implements ApplicationRunner {

    /** The one profile under which any of this happens. */
    static final String PROFILE = "dev";

    private static final Logger log = LoggerFactory.getLogger(DevelopmentAdminBootstrap.class);

    private final MemberProvisioningService provisioningService;
    private final MemberRepository memberRepository;
    private final DevelopmentAdminProperties properties;

    DevelopmentAdminBootstrap(MemberProvisioningService provisioningService,
                              MemberRepository memberRepository,
                              DevelopmentAdminProperties properties) {

        // Validated here rather than in run(), so a half-configured 'dev'
        // profile fails while the context is building instead of leaving an
        // application that starts, serves a login page, and has no account.
        properties.validate();

        this.provisioningService = provisioningService;
        this.memberRepository = memberRepository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = properties.normalisedEmail();

        if (memberRepository.existsByEmailIgnoreCase(email)) {
            log.info("Development admin {} already exists; nothing to bootstrap.", email);
            return;
        }

        Household household = provisioningService.createHousehold(properties.getHouseholdName());
        Member admin = provisioningService.registerMember(
                household.id(), properties.getMemberName(), email, properties.getPassword(), Role.ADMIN);

        log.info("Bootstrapped development household '{}' with {} member {}. "
                        + "Sign in at /login using that address and the password from DEV_ADMIN_PASSWORD.",
                household.name(), admin.role(), admin.email());
    }
}
