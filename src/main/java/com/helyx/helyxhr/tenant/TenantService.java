package com.helyx.helyxhr.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant lookup behind a Caffeine cache: the resolution filter hits this on every request, and the
 * tenant row changes rarely. Misses are cached too (as empty Optionals) so a flood of requests for
 * unknown subdomains cannot hammer the database. Consequence: slug changes, suspensions, and new
 * tenants take up to CACHE_TTL to become visible — acceptable at this scale (ADR 0003).
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
  @Transactional(readOnly = true)
  public Optional<TenantSummary> bySlug(String slug) {
    return bySlug.get(slug, s -> repository.findBySlugAndDeletedAtIsNull(s).map(TenantSummary::of));
  }

  // Package-private: only tests need to force freshness.
  void evictCache() {
    bySlug.invalidateAll();
  }
}
