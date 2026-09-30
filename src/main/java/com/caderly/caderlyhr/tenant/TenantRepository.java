package com.caderly.caderlyhr.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlugAndDeletedAtIsNull(String slug);

    /** Backs {@link TenantFacade#listActiveTenantIds()} — cross-tenant scheduled jobs' fan-out list. */
    List<Tenant> findAllByDeletedAtIsNullAndSuspendedFalse();

    /**
     * Super Admin tenant-creation uniqueness check (Phase 1.13). Deliberately not scoped by
     * {@code deletedAt}: {@code tenant.slug} carries a bare {@code UNIQUE} constraint with no
     * partial-index exception (V202607241000), so a slug is unavailable forever once used, even
     * once its tenant is soft-deleted.
     */
    boolean existsBySlug(String slug);
}
