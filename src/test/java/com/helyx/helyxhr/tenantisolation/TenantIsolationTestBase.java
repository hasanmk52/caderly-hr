package com.helyx.helyxhr.tenantisolation;

import com.helyx.helyxhr.TestcontainersConfiguration;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Two-tenant fixture required by CLAUDE.md §8: seeds tenants A and B so every isolation test can
 * prove that reads/writes as one tenant never touch the other. Extend this for any test that
 * verifies tenant scoping.
 */
@Import(TestcontainersConfiguration.class)
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

  protected UUID seedTenant(String slug, String name) {
    return tenantRepository.save(new Tenant(slug, name)).getId();
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

  private static String shortRandom() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
