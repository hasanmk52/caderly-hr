package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.audit.EntityAuditListener;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.identity.InviteService;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The Super Admin console's "create tenant + first Admin" flow in one call (PRD FR-1.8).
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional} at the method level. {@link
 * TenantFacade#createTenant} already runs its own write under {@code
 * TenantContext.runAsSystem} in its own transaction, so by the time it returns here, no
 * transaction is open on this thread. Only after that do we set {@code TenantContext} to the new
 * tenant and call {@link InviteService#invite}, whose own {@code @Transactional} then opens a
 * <em>fresh</em> transaction with the tenant already resolved — exactly the ordering Global
 * Constraint 1 requires, since {@code TenantSessionVariableListener.afterBegin} reads {@code
 * TenantContext} at the moment a transaction begins. Wrapping this method in {@code @Transactional}
 * would open one shared transaction across both steps, starting before {@code
 * TenantContext.set(tenantId)} runs, and would throw {@code IllegalStateException} the moment
 * {@code invite}'s work tried to run inside it.
 */
@Service
public class TenantProvisioningService {

    private final TenantFacade tenants;
    private final InviteService invites;
    private final EntityAuditListener auditListener;
    private final ObjectMapper mapper;

    TenantProvisioningService(
            TenantFacade tenants, InviteService invites, EntityAuditListener auditListener, ObjectMapper mapper) {
        this.tenants = tenants;
        this.invites = invites;
        this.auditListener = auditListener;
        this.mapper = mapper;
    }

    /**
     * Creates a new tenant and invites its first Admin.
     *
     * <p>No {@code Employee} row is created for the invited Admin: {@code people.Employee.userId}
     * is a nullable plain column with no reverse FK, so an {@code AppUser} can stand alone until
     * (if ever) an Admin later creates a matching Employee record by hand.
     *
     * <p>Writes the tenant-lifecycle {@code audit_entry} row itself (CLAUDE.md §5 rule 6 — every
     * Super-Admin write bypassing tenancy must be audited) rather than inside {@link
     * TenantFacade#createTenant}: {@code tenant} sits below {@code audit} in the package ordering
     * ({@code audit.EntityAuditListener} already imports {@code tenant.TenantContext}), so {@code
     * tenant} depending on {@code audit} the other way would be a genuine ArchUnit-forbidden cycle.
     * This class already sits above both (in {@code superadmin}), so it is the correct place for
     * the two to meet.
     *
     * @param actorUserId the Super Admin who initiated this creation (audit trail attribution).
     * @throws com.caderly.caderlyhr.common.ConflictException if {@code slug} is already taken.
     */
    public UUID provision(
            String slug,
            String name,
            String timezone,
            int weekendDays,
            @Nullable String logoUrl,
            String firstAdminEmail,
            String appBaseUrl,
            UUID actorUserId) {
        UUID tenantId = tenants.createTenant(slug, name, timezone, weekendDays, logoUrl);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("slug", slug);
        after.put("name", name);
        auditListener.recordManualEvent(
                tenantId, actorUserId, "SUPER_ADMIN", "Tenant", tenantId.toString(), Action.CREATE, toJson(after));

        TenantContext.set(tenantId);
        try {
            invites.invite(firstAdminEmail, Set.of(Role.ADMIN), appBaseUrl, name);
        } finally {
            TenantContext.clear();
        }
        return tenantId;
    }

    private String toJson(Map<String, Object> payload) {
        return mapper.writeValueAsString(payload);
    }
}
