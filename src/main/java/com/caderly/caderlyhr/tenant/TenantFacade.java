package com.caderly.caderlyhr.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module read access to tenants. This is the project's general rule for all modules: don't
 * reach into another module's internals, go through its facade
 */
public interface TenantFacade {

    Optional<TenantSummary> bySlug(String slug);

    /**
     * Every active (not deleted, not suspended) tenant id — the fan-out list for a cross-tenant
     * scheduled job (e.g. {@code people.EmployeeTerminationJob}). Callers must set {@code
     * TenantContext} to each id in turn before reading tenant-scoped data: unlike {@code
     * TenantContext.runAsSystem}, RLS is not bypassed for this data (ADR 0003) — real per-tenant
     * context is what makes each iteration's reads legitimate rather than a bypass attempt.
     */
    List<UUID> listActiveTenantIds();

    /**
     * The current tenant's weekend-day bitmask (PRD §12.5, §21) — decoded by {@code
     * timeoff.LeaveDurationCalculator.decodeWeekend}. Resolved from {@link TenantContext#require()};
     * {@code tenant} has no {@code tenant_id} of its own to filter by, so no {@code runAsSystem} is
     * needed the way {@link #bySlug} and {@link #listActiveTenantIds()} require.
     */
    int currentWeekendDays();
}
