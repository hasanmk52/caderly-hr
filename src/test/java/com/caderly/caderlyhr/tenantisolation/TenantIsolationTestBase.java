package com.caderly.caderlyhr.tenantisolation;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

/**
 * Two-tenant fixture required by CLAUDE.md §8: seeds tenants A and B so every isolation test can
 * prove that reads/writes as one tenant never touch the other. Extend this for any test that
 * verifies tenant scoping.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
public abstract class TenantIsolationTestBase {

    @Autowired protected TenantRepository tenantRepository;

    protected UUID tenantA;
    protected UUID tenantB;

    @BeforeEach
    void seedTenants() {
        // Unique slugs per test: the Testcontainers context (and its DB) is shared across
        // test classes, and the tenant table has no cleanup between tests by design.
        tenantA = seedTenant("tenant-a-" + shortRandom(), "Tenant A");
        tenantB = seedTenant("tenant-b-" + shortRandom(), "Tenant B");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // Seeding a tenant is itself cross-tenant work (there's no tenant yet to be "in") — same
    // reasoning as TenantService#bySlug (ADR 0004): runAsSystem, not a real tenant context.
    protected UUID seedTenant(String slug, String name) {
        return TenantContext.runAsSystem(
                "test: seed tenant fixture", () -> tenantRepository.save(new Tenant(slug, name)).getId());
    }

    /** Runs the action with the given tenant active, always clearing afterwards. */
    protected <T> T asTenant(UUID tenantId, java.util.function.Supplier<T> action) {
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** {@code void}-returning counterpart to {@link #asTenant(UUID, java.util.function.Supplier)}. */
    protected void asTenant(UUID tenantId, Runnable action) {
        TenantContext.set(tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    private static String shortRandom() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
