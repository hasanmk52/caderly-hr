package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.audit.EntityAuditListener;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.tenant.TenantSummary;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the start/end {@code audit_entry} pair for a Super Admin support session (PRD FR-1.8).
 *
 * <p>Extracted out of {@link ImpersonationController} so the closing ({@code DELETE}) row can be
 * written from a second place — {@link ImpersonationLogoutHandler}, which fires when an
 * impersonated operator ends their session through the ordinary tenant {@code POST /logout} rather
 * than the dedicated "End impersonation" link — without duplicating the entity shape, the JSON
 * payload construction, or the correlation-id convention. Both callers must produce byte-identical
 * rows for the same kind of event; one shared class is what makes that true by construction.
 */
@Component
class ImpersonationSessionAuditor {

    /** {@code audit_entry.entity_type} for the start/end pair; there is no entity behind them. */
    private static final String AUDIT_ENTITY_TYPE = "ImpersonationSession";

    private final EntityAuditListener auditListener;
    private final ObjectMapper mapper;

    ImpersonationSessionAuditor(EntityAuditListener auditListener, ObjectMapper mapper) {
        this.auditListener = auditListener;
        this.mapper = mapper;
    }

    void recordStart(
            TenantSummary tenant, UUID superAdminId, String superAdminEmail, String targetAdminEmail, String correlationId) {
        auditListener.recordManualEvent(
                tenant.id(),
                superAdminId,
                "SUPER_ADMIN",
                AUDIT_ENTITY_TYPE,
                correlationId,
                Action.CREATE,
                sessionJson(superAdminEmail, targetAdminEmail, tenant.slug()));
    }

    void recordEnd(TenantSummary tenant, ImpersonationSession impersonated) {
        auditListener.recordManualEvent(
                tenant.id(),
                impersonated.superAdminId(),
                "SUPER_ADMIN",
                AUDIT_ENTITY_TYPE,
                impersonated.correlationId(),
                Action.DELETE,
                sessionJson(impersonated.superAdminEmail(), impersonated.impersonatedEmail(), tenant.slug()));
    }

    /**
     * Built through the application {@code ObjectMapper} rather than concatenated, so an
     * apostrophe in an address cannot produce a row Postgres rejects as malformed {@code jsonb}.
     */
    private String sessionJson(String superAdminEmail, String targetAdminEmail, String tenantSlug) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("superAdminEmail", superAdminEmail);
        payload.put("targetAdminEmail", targetAdminEmail);
        payload.put("tenantSlug", tenantSlug);
        return mapper.writeValueAsString(payload);
    }
}
