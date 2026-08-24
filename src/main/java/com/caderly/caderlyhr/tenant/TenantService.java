package com.caderly.caderlyhr.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

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

    private final Cache<String, Optional<TenantSummary>> bySlug =
            Caffeine.newBuilder().maximumSize(CACHE_MAX_SIZE).expireAfterWrite(CACHE_TTL).build();

    TenantService(TenantRepository repository) {
        this.repository = repository;
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
        UUID tenantId = TenantContext.require();
        return repository
                .findById(tenantId)
                .map(Tenant::getWeekendDays)
                .orElseThrow(() -> new IllegalStateException("No tenant row for id " + tenantId));
    }

    // Package-private: only tests need to force freshness.
    void evictCache() {
        bySlug.invalidateAll();
    }
}
