package com.caderly.caderlyhr.identity;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.util.Optional;
import java.util.UUID;

// No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4).
public interface DashboardLayoutRepository extends TenantAwareRepository<DashboardLayout> {

    Optional<DashboardLayout> findByUserId(UUID userId);
}
