package com.caderly.caderlyhr.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlugAndDeletedAtIsNull(String slug);

    /** Backs {@link TenantFacade#listActiveTenantIds()} — cross-tenant scheduled jobs' fan-out list. */
    List<Tenant> findAllByDeletedAtIsNullAndSuspendedFalse();
}
