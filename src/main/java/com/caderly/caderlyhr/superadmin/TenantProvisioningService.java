package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.identity.InviteService;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The Super Admin console's "create tenant + first Admin" flow in one call (PRD FR-1.8).
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional} at the method level. {@link
 * TenantFacade#createTenant} already runs its own write under {@code
 * TenantContext.runAsSystem} in its own transaction (Task 1), so by the time it returns here, no
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

    TenantProvisioningService(TenantFacade tenants, InviteService invites) {
        this.tenants = tenants;
        this.invites = invites;
    }

    /**
     * Creates a new tenant and invites its first Admin.
     *
     * <p>No {@code Employee} row is created for the invited Admin: {@code people.Employee.userId}
     * is a nullable plain column with no reverse FK, so an {@code AppUser} can stand alone until
     * (if ever) an Admin later creates a matching Employee record by hand.
     *
     * @throws com.caderly.caderlyhr.common.ConflictException if {@code slug} is already taken.
     */
    public UUID provision(
            String slug,
            String name,
            String timezone,
            int weekendDays,
            @Nullable String logoUrl,
            String firstAdminEmail,
            String appBaseUrl) {
        UUID tenantId = tenants.createTenant(slug, name, timezone, weekendDays, logoUrl);
        TenantContext.set(tenantId);
        try {
            invites.invite(firstAdminEmail, Set.of(Role.ADMIN), appBaseUrl, name);
        } finally {
            TenantContext.clear();
        }
        return tenantId;
    }
}
