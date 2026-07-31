package com.helyx.helyxhr.common;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository for tenant-scoped entities (CLAUDE.md §10). Scoping itself comes from the
 * Hibernate tenant filter + RLS, enabled per service call by TenantEnforcementAspect — repositories
 * never add manual tenant_id predicates (CLAUDE.md §5 rule 4).
 */
@NoRepositoryBean
public interface TenantAwareRepository<T extends TenantAwareEntity>
    extends JpaRepository<T, UUID> {}
