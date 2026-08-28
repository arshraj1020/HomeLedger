package com.householdledger;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Module boundary enforcement (PRD §6.1): no module may reach into another
 * module's {@code internal} package. {@code ledger.internal} and — as of
 * Phase 2 — {@code identity.internal} are both guarded; the reporting
 * module gains its rule when it acquires internals in Phase 6.
 */
class ModuleBoundaryArchTest {

    private static final String BASE = "com.householdledger";

    @Test
    void ledgerInternalIsNotReachedFromOutsideLedger() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideOutsideOfPackage(BASE + ".ledger..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".ledger.internal..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * Phase 2's addition. The web layer talks to {@code identity.api} only;
     * JPA entities, repositories, the JWT services and the security config
     * all stay behind the module boundary. Without this rule it would be
     * trivially easy for a controller in a later phase to inject
     * {@code MemberRepository} directly and leak a password hash into a DTO.
     */
    @Test
    void identityInternalIsNotReachedFromOutsideIdentity() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideOutsideOfPackage(BASE + ".identity..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".identity.internal..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * The ledger module must not depend on identity at all. Household
     * scoping is passed in as a plain {@code UUID} by the caller (PRD §FR-1),
     * so the ledger never needs to know what a Member is — keeping the core
     * (PRD §6.1: "← core") free of authentication concerns.
     */
    @Test
    void ledgerDoesNotDependOnIdentity() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".ledger..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".identity..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * Phase 3 introduces the project's first cross-module dependency:
     * {@code identity} calls {@code ledger.api.AccountService} to seed a new
     * household's chart of accounts (PRD §FR-2). That direction is allowed;
     * this rule pins the fact that it stays confined to the published API and
     * the framework-free domain types, never {@code ledger.internal}.
     *
     * <p>Combined with {@link #ledgerDoesNotDependOnIdentity()}, the
     * dependency is strictly one-way, so the module graph stays acyclic.
     */
    @Test
    void identityMayUseLedgerApiButNotLedgerInternals() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".identity..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".ledger.internal..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * PRD §7: "no controller reaching a repository directly". Phase 3 adds
     * the first controller that could plausibly be tempted to — account
     * listing is a thin read — so the rule earns its place now.
     */
    @Test
    void controllersDoNotReachRepositoriesDirectly() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".web..")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    @Test
    void layeredArchitectureIsRespected() {
        // Documents the intended shape (PRD §6.1). Tightened as each module
        // gains real classes; reporting is still a placeholder package.
        ArchRule rule = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Web").definedBy(BASE + ".web..")
                .layer("Ledger API").definedBy(BASE + ".ledger.api..")
                .layer("Ledger Internal").definedBy(BASE + ".ledger.internal..")
                .layer("Identity API").definedBy(BASE + ".identity.api..")
                .layer("Identity Internal").definedBy(BASE + ".identity.internal..")
                .whereLayer("Ledger Internal").mayOnlyBeAccessedByLayers("Ledger API")
                .whereLayer("Identity Internal").mayOnlyBeAccessedByLayers("Identity API");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }
}
