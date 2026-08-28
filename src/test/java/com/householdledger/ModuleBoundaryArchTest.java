package com.householdledger;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Module boundary enforcement (PRD §6.1): no module may reach into another
 * module's {@code internal} package. Only {@code ledger.internal} exists
 * today; the rule is written generically so it keeps applying as
 * identity/reporting/web grow their own internal packages in later phases.
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

    @Test
    void layeredArchitectureIsRespected() {
        // Documents the intended shape (PRD §6.1). Loosely specified for now
        // since only package-info placeholders exist; tightened as each
        // module gains real classes in later phases.
        ArchRule rule = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Web").definedBy(BASE + ".web..")
                .layer("Ledger API").definedBy(BASE + ".ledger.api..")
                .layer("Ledger Internal").definedBy(BASE + ".ledger.internal..")
                .whereLayer("Ledger Internal").mayOnlyBeAccessedByLayers("Ledger API");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE));
    }
}
