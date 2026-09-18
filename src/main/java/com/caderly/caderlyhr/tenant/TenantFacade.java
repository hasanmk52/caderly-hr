package com.caderly.caderlyhr.tenant;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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

    /**
     * The current tenant's name and (optional) logo, for the transactional-email chrome (PRD
     * §17.1). There is no primary colour here on purpose: one Caderly brand serves every tenant
     * and a tenant logo is a guest on it, not a replacement for it (ADR 0016).
     */
    TenantBranding currentBranding();

    /**
     * The current tenant's configured zone. UI_Guidelines §11: stored UTC, displayed in the
     * tenant's timezone — a rule the JVM default zone cannot satisfy for a multi-tenant app.
     */
    ZoneId currentTimezone();

    /** PRD FR-9.3's category switches for the current tenant. */
    NotificationSettings currentNotificationSettings();

    /** Admin save from {@code /admin/notifications}. */
    void updateNotificationSettings(NotificationSettings settings);

    record TenantBranding(String name, @Nullable String logoUrl) {}

    record NotificationSettings(
            boolean holidayReminder, boolean documentExpiry, boolean birthday, boolean workAnniversary) {}
}
