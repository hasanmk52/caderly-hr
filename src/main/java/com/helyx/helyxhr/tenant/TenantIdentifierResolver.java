package com.helyx.helyxhr.tenant;

import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Tells Hibernate which tenant is current for {@code @TenantId} discriminator multi-tenancy
 * (CLAUDE.md §5 rule 4). Hibernate calls this itself — when opening a session and when generating
 * {@code tenant_id} on insert — so there is no manual {@code session.enableFilter(...)} call
 * anywhere in application code.
 *
 * <p>Fail-closed (CLAUDE.md §5 rule 6): a genuinely missing tenant still throws {@link
 * IllegalStateException} via {@link TenantContext#require()}. The one exception is explicit system
 * mode ({@link TenantContext#runAsSystem}) — audited, cross-tenant work like {@link
 * TenantService#bySlug} that must run before any tenant is known — which gets {@link #NO_TENANT}, a
 * fixed value no real tenant can ever have (tenant ids are {@code UuidGenerator.Style.VERSION_7},
 * never nil), so Hibernate still has a session-scoped identifier to work with. Reads against
 * {@code @TenantId} entities in system mode are filtered to {@code tenant_id = NO_TENANT} (zero
 * rows — real cross-tenant reads are still deferred to the Super Admin console, per ADR 0003).
 *
 * <p>Hibernate resolves a tenant identifier for EVERY session it opens, including ones Spring Data
 * JPA would otherwise open at application-startup time to validate repository queries — before any
 * tenant or system-mode context exists. {@code spring.data.jpa.repositories.bootstrap-mode=lazy}
 * (application.yml) defers that validation to each repository's first real invocation instead, so
 * this resolver only ever gets called from inside an actual request or {@code runAsSystem} block.
 */
@Component
class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    static final UUID NO_TENANT = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.isSystem() ? NO_TENANT : TenantContext.require();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
