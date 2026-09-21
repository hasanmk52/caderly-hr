package com.caderly.caderlyhr.tenant;

import com.caderly.caderlyhr.common.ConflictException;
import com.caderly.caderlyhr.common.NotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant lookup behind a Caffeine cache: the resolution filter hits this on every request, and the
 * tenant row changes rarely. Misses are cached too (as empty Optionals) so a flood of requests for
 * unknown subdomains cannot hammer the database. Consequence: slug changes, suspensions, and new
 * tenants take up to CACHE_TTL to become visible — acceptable at this scale (ADR 0003).
 *
 * <p>The lookup runs via {@code TenantContext.runAsSystem} (CLAUDE.md §5 rule 6, audited): {@code
 * tenant} has no {@code tenant_id} and this call is what establishes tenant context in the first
 * place, so it must run before any tenant is known (see ADR 0004). Deliberately not
 * {@code @Transactional} at this method level — that would start the transaction (and resolve a
 * Hibernate tenant identifier) before {@code runAsSystem} sets system mode. {@link
 * TenantRepository}'s own generated implementation already opens its own short-lived transaction
 * per call.
 */
@Service
public class TenantService implements TenantFacade {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long CACHE_MAX_SIZE = 1_000;

    private final TenantRepository repository;
    private final Clock clock;

    private final Cache<String, Optional<TenantSummary>> bySlug =
            Caffeine.newBuilder().maximumSize(CACHE_MAX_SIZE).expireAfterWrite(CACHE_TTL).build();

    TenantService(TenantRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Optional<TenantSummary> bySlug(String slug) {
        return bySlug.get(
                slug,
                s ->
                        TenantContext.runAsSystem(
                                "resolve tenant by slug for request routing",
                                () -> repository.findBySlugAndDeletedAtIsNull(s).map(TenantSummary::of)));
    }

    @Override
    public List<UUID> listActiveTenantIds() {
        return TenantContext.runAsSystem(
                "list active tenants for scheduled job fan-out",
                () ->
                        repository.findAllByDeletedAtIsNullAndSuspendedFalse().stream()
                                .map(tenant -> java.util.Objects.requireNonNull(tenant.getId()))
                                .toList());
    }

    @Override
    public int currentWeekendDays() {
        return requireCurrent().getWeekendDays();
    }

    @Override
    public TenantBranding currentBranding() {
        Tenant tenant = requireCurrent();
        return new TenantBranding(tenant.getName(), tenant.getLogoUrl());
    }

    @Override
    public ZoneId currentTimezone() {
        return ZoneId.of(requireCurrent().getTimezone());
    }

    @Override
    public NotificationSettings currentNotificationSettings() {
        Tenant tenant = requireCurrent();
        return new NotificationSettings(
                tenant.isNotifyHolidayReminder(),
                tenant.isNotifyDocumentExpiry(),
                tenant.isNotifyBirthday(),
                tenant.isNotifyWorkAnniversary());
    }

    /**
     * Not cached, unlike {@link #bySlug}: {@code TenantSummary} is what the 60s cache holds and it
     * carries none of these flags, so an Admin toggling a category sees the effect on the next
     * enqueue rather than up to a minute later.
     */
    @Override
    @Transactional
    public void updateNotificationSettings(NotificationSettings settings) {
        Tenant tenant = requireCurrent();
        tenant.updateNotificationSettings(
                settings.holidayReminder(),
                settings.documentExpiry(),
                settings.birthday(),
                settings.workAnniversary());
        repository.save(tenant);
    }

    /**
     * Super Admin tenant provisioning (PRD FR-1.8). Runs entirely under {@code runAsSystem}, same
     * as {@link #bySlug} — there is no tenant to be "in" yet — and deliberately not {@code
     * @Transactional} at this method level for the same reason {@link #bySlug} isn't: {@link
     * TenantRepository}'s generated methods ({@code existsBySlug}, {@code save}) each open their
     * own short-lived transaction, and by the time either opens, {@code runAsSystem} has already
     * armed system mode — the exact ordering Global Constraint 1 requires. A shared
     * {@code @Transactional} spanning both calls would instead open one transaction before
     * {@code runAsSystem} runs, which is the trap this method must avoid.
     */
    @Override
    public UUID createTenant(String slug, String name, String timezone, int weekendDays, @Nullable String logoUrl) {
        UUID tenantId =
                TenantContext.runAsSystem(
                        "superadmin: create tenant " + slug,
                        () -> {
                            if (repository.existsBySlug(slug)) {
                                throw new ConflictException(
                                        "TENANT_SLUG_TAKEN", "A tenant with that slug already exists");
                            }
                            return repository.save(new Tenant(slug, name, timezone, weekendDays, logoUrl)).getId();
                        });
        evictCache();
        return Objects.requireNonNull(tenantId);
    }

    @Override
    public void suspend(UUID tenantId) {
        TenantContext.runAsSystem(
                "superadmin: suspend tenant " + tenantId,
                () -> {
                    Tenant tenant = findByIdOrThrow(tenantId);
                    tenant.suspend();
                    repository.save(tenant);
                    return null;
                });
        evictCache();
    }

    @Override
    public void reinstate(UUID tenantId) {
        TenantContext.runAsSystem(
                "superadmin: reinstate tenant " + tenantId,
                () -> {
                    Tenant tenant = findByIdOrThrow(tenantId);
                    tenant.reinstate();
                    repository.save(tenant);
                    return null;
                });
        evictCache();
    }

    @Override
    public void softDelete(UUID tenantId) {
        TenantContext.runAsSystem(
                "superadmin: soft-delete tenant " + tenantId,
                () -> {
                    Tenant tenant = findByIdOrThrow(tenantId);
                    tenant.softDelete(clock.instant());
                    repository.save(tenant);
                    return null;
                });
        evictCache();
    }

    @Override
    public List<TenantAdminView> listAllForAdmin() {
        return TenantContext.runAsSystem(
                "superadmin: list all tenants",
                () -> repository.findAll().stream().map(TenantService::toAdminView).toList());
    }

    private Tenant findByIdOrThrow(UUID tenantId) {
        return repository
                .findById(tenantId)
                .orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "Tenant not found"));
    }

    private static TenantAdminView toAdminView(Tenant tenant) {
        return new TenantAdminView(
                Objects.requireNonNull(tenant.getId()),
                tenant.getSlug(),
                tenant.getName(),
                tenant.isSuspended(),
                tenant.getDeletedAt(),
                Objects.requireNonNull(tenant.getCreatedAt()));
    }

    private Tenant requireCurrent() {
        UUID tenantId = TenantContext.require();
        return repository
                .findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("No tenant row for id " + tenantId));
    }

    // Package-private: only tests need to force freshness.
    void evictCache() {
        bySlug.invalidateAll();
    }
}
