package com.householdledger;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Module boundary enforcement (PRD §6.1): no module may reach into another
 * module's {@code internal} package. All three feature modules are guarded:
 * {@code ledger.internal} (Phase 1), {@code identity.internal} (Phase 2) and
 * {@code reporting.internal} (Phase 6). The rules also pin the direction of
 * every cross-module dependency, so the graph stays acyclic.
 *
 * <p>Phase 7 adds the browser UI under {@code web.ui} and four rules for it:
 * it renders views rather than response bodies, it never touches JWTs or
 * persistence, and nothing depends on it.
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

    /**
     * Phase 4 moved the {@code Clock} bean into {@code shared}, because the
     * ledger now needs it too (PRD §FR-3's future-date tolerance) and a bean
     * two modules depend on has no business behind one module's internal
     * boundary.
     *
     * <p>{@code shared} is depended upon by everything, so it must depend on
     * nothing: PRD §6.1 scopes it to "common errors, base types, config". A
     * shared module that reached back into a feature module would make every
     * other boundary meaningless.
     */
    @Test
    void sharedDependsOnNoFeatureModule() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".shared..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(BASE + ".ledger..", BASE + ".identity..",
                        BASE + ".reporting..", BASE + ".web..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * Every {@code domain} and {@code api} package stays free of Spring,
     * JPA and Jakarta types.
     *
     * <p>Phase 5 is where this stopped being incidental and became a real
     * choice: paging and filtering arrive from Spring Data, and the obvious
     * shortcut would have been to return Spring's {@code Page} and accept its
     * {@code Pageable} straight through {@code LedgerService}. That would put
     * the persistence framework into the module's published contract, so the
     * web layer, the Phase 7 UI and any future consumer would compile against
     * Spring Data purely because of how the ledger stores things today.
     * {@code PageResult}, {@code PageSpec} and {@code TransactionFilter} exist
     * to keep that boundary, and this rule is what stops the shortcut being
     * taken later by accident.
     *
     * <p>It also keeps the domain unit-testable with no context and no
     * database — which is why the Phase 1 and Phase 4 property tests run in
     * milliseconds.
     */
    @Test
    void domainAndApiPackagesAreFrameworkFree() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAnyPackage(
                        BASE + ".ledger.domain..", BASE + ".ledger.api..",
                        BASE + ".identity.domain..", BASE + ".identity.api..",
                        BASE + ".reporting.domain..", BASE + ".reporting.api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                        "jakarta.validation..", "org.hibernate..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * Phase 6 gives the reporting module internals for the first time (PRD
     * §6.1 lists {@code reporting/} as its own module). Nothing outside it
     * may reach them — the web layer talks to
     * {@code reporting.api.ReportingService} and nothing else.
     */
    @Test
    void reportingInternalIsNotReachedFromOutsideReporting() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideOutsideOfPackage(BASE + ".reporting..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".reporting.internal..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * Reporting reads the ledger through its published API, never its
     * internals.
     *
     * <p>This is the rule that shaped Phase 6's design. Reporting needs
     * aggregate sums over postings, and the shortcut would have been to give
     * the reporting module its own JPA mappings for the same tables — two
     * definitions of one schema, free to drift apart. Instead the ledger
     * publishes the aggregates ({@code LedgerReportQueries}) and reporting
     * composes them, so all SQL stays in one module.
     */
    @Test
    void reportingUsesLedgerApiButNotLedgerInternals() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".reporting..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".ledger.internal..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * Reporting never touches identity. Household scoping arrives as a plain
     * {@code UUID} from the caller, exactly as it does for the ledger, so the
     * reporting module has no notion of who a member is.
     */
    @Test
    void reportingDoesNotDependOnIdentity() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".reporting..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".identity..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * The ledger core stays unaware of reporting, keeping the dependency
     * one-way and the graph acyclic.
     */
    @Test
    void ledgerDoesNotDependOnReporting() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".ledger..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".reporting..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * Phase 7's UI controllers render views; they must not also be REST
     * endpoints.
     *
     * <p>This is what keeps the two error contracts apart. The API's
     * {@code @RestControllerAdvice} classes are scoped to
     * {@code @RestController}, so a UI controller annotated that way by
     * accident would start answering a browser navigation with an RFC 7807
     * JSON document instead of a page — and would do it silently, because the
     * response is still a valid HTTP 404.
     */
    @Test
    void uiControllersRenderViewsRatherThanSerialisingResponseBodies() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".web.ui..")
                .should().beAnnotatedWith(org.springframework.web.bind.annotation.RestController.class);

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * No JWT reaches the browser UI.
     *
     * <p>PRD §FR-1's access and refresh tokens are an API credential. The
     * browser is authenticated by a server-side session instead, and the
     * reason that matters is that a token the UI could hold is a token a
     * script in the page could read. A UI class that imported the JWT library
     * would be the first step towards putting one in a cookie or a hidden
     * field, so the boundary is enforced rather than remembered.
     */
    @Test
    void theBrowserUiNeverTouchesJwts() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".web.ui..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.jsonwebtoken..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * The UI composes module APIs; it never reaches persistence.
     *
     * <p>The tempting shortcut on a page that lists accounts with balances is
     * a repository call or a JPA entity in a view model — which would put a
     * lazily-loaded entity in front of a template with {@code open-in-view}
     * switched off, and give the UI a second, divergent way to read the same
     * tables. {@code controllersDoNotReachRepositoriesDirectly} already covers
     * the repository half; this covers the frameworks themselves.
     */
    @Test
    void theUiDoesNotDependOnPersistenceFrameworks() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".web.ui..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.springframework.data..",
                        "org.hibernate..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }

    /**
     * The UI is a leaf: the JSON API layer does not depend on it.
     *
     * <p>Sharing a view model or a formatter "just this once" would tie the
     * API's response shape to how a page happens to look, and PRD §11 lists
     * replacing Thymeleaf with a React frontend as future scope. The UI has to
     * be removable.
     */
    @Test
    void theJsonApiLayerDoesNotDependOnTheUi() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that().resideInAPackage(BASE + ".web")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".web.ui..");

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
                .layer("Web").definedBy(BASE + ".web", BASE + ".web.dto..")
                .layer("Web UI").definedBy(BASE + ".web.ui..")
                .layer("Ledger API").definedBy(BASE + ".ledger.api..")
                .layer("Ledger Internal").definedBy(BASE + ".ledger.internal..")
                .layer("Identity API").definedBy(BASE + ".identity.api..")
                .layer("Identity Internal").definedBy(BASE + ".identity.internal..")
                .layer("Reporting API").definedBy(BASE + ".reporting.api..")
                .layer("Reporting Internal").definedBy(BASE + ".reporting.internal..")
                .whereLayer("Ledger Internal").mayOnlyBeAccessedByLayers("Ledger API")
                .whereLayer("Identity Internal").mayOnlyBeAccessedByLayers("Identity API")
                .whereLayer("Reporting Internal").mayOnlyBeAccessedByLayers("Reporting API")
                // Phase 7: nothing depends on the UI, so it can be replaced
                // wholesale — PRD §11 lists a React frontend as future scope.
                .whereLayer("Web UI").mayNotBeAccessedByAnyLayer();

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }
}
