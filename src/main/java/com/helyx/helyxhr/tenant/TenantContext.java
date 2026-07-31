package com.helyx.helyxhr.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the active tenant for the current thread (CLAUDE.md §5 rule 3). Populated by {@link
 * TenantResolutionFilter}, which guarantees {@code clear()} in a finally block; test code must do
 * the same.
 */
public final class TenantContext {

    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);

    private static final ThreadLocal<@Nullable UUID> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SYSTEM_MODE = ThreadLocal.withInitial(() -> false);

    private TenantContext() {}

    public static void set(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static Optional<UUID> get() {
        return Optional.ofNullable(CURRENT_TENANT.get());
    }

    /** Fail-closed accessor: tenant-scoped work without an active tenant is a bug, not a fallback. */
    public static UUID require() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    """
                  No tenant in context. Tenant-scoped operations must run within a resolved tenant \
                  request or TenantContext.runAsSystem(...)""");
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        SYSTEM_MODE.remove();
    }

    public static boolean isSystem() {
        return SYSTEM_MODE.get();
    }

    /**
     * Cross-tenant/system bypass (CLAUDE.md §5 rule 6). The app-layer tenant filter is skipped;
     * Postgres RLS still applies until a dedicated bypass role exists (ADR 0003). Every use is logged
     * with a reason; a persisted audit entry lands with the audit module in Phase 1.11.
     */
    public static <T> T runAsSystem(String reason, Supplier<T> action) {
        UUID previousTenant = CURRENT_TENANT.get();
        boolean previousSystem = SYSTEM_MODE.get();
        log.info("SYSTEM tenant bypass: {}", reason);
        SYSTEM_MODE.set(true);
        CURRENT_TENANT.remove();
        try {
            return action.get();
        }
        // Always restores both the tenant and the system flag to what they were before
        finally {
            SYSTEM_MODE.set(previousSystem);
            if (previousTenant != null) {
                CURRENT_TENANT.set(previousTenant);
            } else {
                CURRENT_TENANT.remove();
            }
        }
    }
}
