package com.caderly.caderlyhr.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.caderly.caderlyhr.common.TenantAwareEntity;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.lang.annotation.Annotation;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.caderly.caderlyhr");

    @Test
    void packages_haveNoCycles() {
        ArchRule rule = slices().matching("com.caderly.caderlyhr.(*)..").should().beFreeOfCycles();
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
                                "com.caderly.caderlyhr.tenant..",
                                "com.caderly.caderlyhr.superadmin..",
                                "com.caderly.caderlyhr.common..",
                                "..system..")
                        .should()
                        .beAssignableTo(TenantAwareEntity.class)
                        .because("every tenant-scoped entity must extend TenantAwareEntity (CLAUDE.md §5)")
                        .allowEmptyShould(true);
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void everyRequestMappingMethod_isAnnotatedWithPreAuthorize() {
        // CLAUDE.md §6 A01. Public endpoints satisfy this with permitAll() rather than by
        // omission, so "anyone may call this" is always a decision someone wrote down — and
        // a new endpoint cannot default to whatever the URL rules in SecurityConfig happen
        // to say.
        ArchRule rule =
                methods()
                        .that(are(annotatedWithAnyRequestMapping()))
                        .should(beAnnotatedWithPreAuthorize())
                        .because("every controller method needs an explicit @PreAuthorize (CLAUDE.md §6 A01)")
                        .allowEmptyShould(true);
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void nativeQueriesAndJdbc_areConfinedToRepositories() {
        // CLAUDE.md §8. Native SQL bypasses Hibernate's @TenantId restriction entirely, so it
        // is the one place a cross-tenant read could reappear after ADR 0004. Repositories are
        // the only place it is reviewable.
        ArchRule rule =
                noClasses()
                        .that()
                        .haveSimpleNameNotEndingWith("Repository")
                        .and()
                        .resideOutsideOfPackage("com.caderly.caderlyhr.tenant..")
                        .should()
                        .callMethod(jakarta.persistence.EntityManager.class, "createNativeQuery", String.class)
                        .orShould()
                        .callMethod(
                                jakarta.persistence.EntityManager.class,
                                "createNativeQuery",
                                String.class,
                                Class.class)
                        .because("native SQL escapes the @TenantId restriction (CLAUDE.md §5, §8)")
                        .allowEmptyShould(true);
        rule.check(PRODUCTION_CLASSES);
    }

    private static DescribedPredicate<JavaMethod> are(DescribedPredicate<JavaMethod> predicate) {
        return predicate;
    }

    private static DescribedPredicate<JavaMethod> annotatedWithAnyRequestMapping() {
        return new DescribedPredicate<>("annotated with a request mapping") {
            @Override
            public boolean test(JavaMethod method) {
                return REQUEST_MAPPINGS.stream().anyMatch(method::isAnnotatedWith);
            }
        };
    }

    private static ArchCondition<JavaMethod> beAnnotatedWithPreAuthorize() {
        return new ArchCondition<>("be annotated with @PreAuthorize") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean annotated = method.isAnnotatedWith(PreAuthorize.class);
                events.add(
                        new SimpleConditionEvent(
                                method,
                                annotated,
                                "%s is not annotated with @PreAuthorize".formatted(method.getFullName())));
            }
        };
    }

    private static final java.util.List<Class<? extends Annotation>> REQUEST_MAPPINGS =
            java.util.List.of(
                    RequestMapping.class,
                    GetMapping.class,
                    PostMapping.class,
                    PutMapping.class,
                    PatchMapping.class,
                    DeleteMapping.class);
}
