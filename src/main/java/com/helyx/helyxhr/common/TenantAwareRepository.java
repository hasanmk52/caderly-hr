package com.helyx.helyxhr.common;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository for tenant-scoped entities (CLAUDE.md §10). Scoping is not this interface's job:
 * Hibernate arms the {@code @TenantId} discriminator on every query it generates for a {@link
 * TenantAwareEntity}, and Postgres RLS backstops it. Repositories therefore never add manual
 * tenant_id predicates (CLAUDE.md §5 rule 4, ADR 0004).
 */
@NoRepositoryBean
public interface TenantAwareRepository<T extends TenantAwareEntity>
    extends JpaRepository<T, UUID> {}
