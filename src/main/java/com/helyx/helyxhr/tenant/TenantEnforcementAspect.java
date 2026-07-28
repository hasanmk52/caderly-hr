package com.helyx.helyxhr.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Defense-in-depth enforcement (PRD §20.4, CLAUDE.md §5 rule 4): before every service method, both
 * isolation layers are armed for the current tenant — the Hibernate {@code tenantFilter} and the
 * Postgres {@code app.tenant_id} session variable that RLS policies check. Services and
 * repositories never write manual {@code WHERE tenant_id = ?} predicates.
 *
 * <p>Order 100 puts this inside the transaction advisor (order 0, see {@link TenantConfig}).
 */
@Aspect
@Component
@Order(100)
class TenantEnforcementAspect {

  @PersistenceContext private EntityManager entityManager;

  // The tenant module itself is excluded: it only touches the cross-tenant tables
  // (tenant, super_admin) and must be callable BEFORE a tenant context exists —
  // resolution is what creates the context in the first place.
  @Around(
      "execution(public * (@org.springframework.stereotype.Service com.helyx.helyxhr..*).*(..))"
          + " && !within(com.helyx.helyxhr.tenant..*)")
  Object enforceTenant(ProceedingJoinPoint joinPoint) throws Throwable {
    if (TenantContext.isSystem()) {
      // Audited bypass (CLAUDE.md §5 rule 6): no app-layer filter; RLS still applies.
      return joinPoint.proceed();
    }
    UUID tenantId = TenantContext.require();
    Session session = entityManager.unwrap(Session.class);
    // Literal filter name, not common.TenantAwareEntity.TENANT_FILTER: importing common
    // here would close the common → tenant → common package cycle (see package-info).
    session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
    // set_config(..., true) = transaction-local (SET LOCAL): the setting dies with the
    // transaction, so a pooled connection can never carry a tenant into the next request.
    session.doWork(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId.toString());
            statement.execute();
          }
        });
    return joinPoint.proceed();
  }
}
