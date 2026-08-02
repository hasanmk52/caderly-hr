package com.helyx.helyxhr.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.helyx.helyxhr.common.TenantAwareEntity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.helyx.helyxhr");

    @Test
    void packages_haveNoCycles() {
        ArchRule rule = slices().matching("com.helyx.helyxhr.(*)..").should().beFreeOfCycles();
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void entities_inTenantScopedPackages_extendTenantAwareEntity() {
        // Default-deny: every package except the cross-tenant roots (tenant, superadmin),
        // common itself, and .system infrastructure sub-packages is tenant-scoped, so any
        // future entity is gated automatically (CLAUDE.md §5 rules 1 and 7).
        //
        // ..system.. is matched by pattern rather than by a class allowlist so the exemption
        // is self-documenting: moving an entity into a .system package is a visible design
        // statement that it is cross-tenant infrastructure (ADR 0005 decision B), and it
        // cannot be granted accidentally by editing a list in this test.
        ArchRule rule =
                classes()
                        .that()
                        .areAnnotatedWith(Entity.class)
                        .and()
                        .resideOutsideOfPackages(
                                "com.helyx.helyxhr.tenant..",
                                "com.helyx.helyxhr.superadmin..",
                                "com.helyx.helyxhr.common..",
                                "..system..")
                        .should()
                        .beAssignableTo(TenantAwareEntity.class)
                        .because("every tenant-scoped entity must extend TenantAwareEntity (CLAUDE.md §5)")
                        .allowEmptyShould(true);
        rule.check(PRODUCTION_CLASSES);
    }
}
