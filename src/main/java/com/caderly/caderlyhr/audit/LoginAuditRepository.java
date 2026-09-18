package com.caderly.caderlyhr.audit;

import com.caderly.caderlyhr.audit.system.LoginAudit;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Plain {@link JpaRepository}, not {@code TenantAwareRepository}: {@link LoginAudit} is
 * system-scoped (ADR 0005 decision B) — an attempt against an unknown email has no tenant-scoped
 * account to restrict a query by. {@link LoginAuditAdminService} supplies the tenant predicate for
 * the Admin viewer half; {@code identity.LoginAttemptService} uses {@link #countRecentFailures}
 * directly for the (email + IP) lockout re-key (ADR 0017).
 */
@Repository
public interface LoginAuditRepository
        extends JpaRepository<LoginAudit, UUID>, JpaSpecificationExecutor<LoginAudit> {

    /**
     * Failed attempts for one (tenant, email, ip) triple since {@code windowStart}. Backed by
     * {@code idx_login_audit_lockout}. Includes the just-written row for the current attempt, so
     * the 5th failure from one IP is the one that observes a count of 5 and trips the lock.
     */
    long countByTenantIdAndEmailAttemptedAndIpAndSuccessFalseAndOccurredAtGreaterThanEqual(
            UUID tenantId, String emailAttempted, String ip, Instant windowStart);
}
