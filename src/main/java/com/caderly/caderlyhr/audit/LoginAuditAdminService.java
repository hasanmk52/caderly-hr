package com.caderly.caderlyhr.audit;

import com.caderly.caderlyhr.audit.system.LoginAudit;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the Admin login-audit viewer, the second tab of {@code /admin/audit-log} (PRD §18.3,
 * FR-11.3). Same tenant-boundary shape as {@link AuditAdminService} — {@code login_audit} is
 * equally system-scoped (ADR 0005 decision B), so the explicit tenant filter below is what makes
 * this a per-tenant view rather than a cross-tenant one.
 */
@Service
public class LoginAuditAdminService {

    public static final int PAGE_SIZE = 25;

    private final LoginAuditRepository logins;
    private final TenantFacade tenants;

    LoginAuditAdminService(LoginAuditRepository logins, TenantFacade tenants) {
        this.logins = logins;
        this.tenants = tenants;
    }

    /** {@code tenants.currentTimezone()} is read per call, not cached at construction — see {@code AuditAdminService}'s Javadoc for why. */
    @Transactional(readOnly = true)
    public Page<LoginAuditRow> list(
            @Nullable Boolean success, @Nullable LocalDate from, @Nullable LocalDate to, int page) {
        UUID tenantId = TenantContext.require();
        ZoneId zone = tenants.currentTimezone();
        Instant fromInstant = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        return logins
                .findAll(
                        forTenant(tenantId).and(withSuccess(success)).and(occurredBetween(fromInstant, toInstant)),
                        PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "occurredAt")))
                .map(row -> LoginAuditRow.of(row, zone));
    }

    private static Specification<LoginAudit> forTenant(UUID tenantId) {
        return (root, query, builder) -> builder.equal(root.get("tenantId"), tenantId);
    }

    private static Specification<LoginAudit> withSuccess(@Nullable Boolean success) {
        return (root, query, builder) ->
                success == null ? builder.conjunction() : builder.equal(root.get("success"), success);
    }

    private static Specification<LoginAudit> occurredBetween(
            @Nullable Instant from, @Nullable Instant to) {
        return (root, query, builder) -> {
            Predicate predicate = builder.conjunction();
            if (from != null) {
                predicate = builder.and(predicate, builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicate = builder.and(predicate, builder.lessThan(root.get("occurredAt"), to));
            }
            return predicate;
        };
    }

    public record LoginAuditRow(
            UUID id,
            ZonedDateTime occurredAt,
            @Nullable String emailAttempted,
            boolean success,
            @Nullable String failureReason,
            @Nullable String ip) {

        static LoginAuditRow of(LoginAudit row, ZoneId zone) {
            return new LoginAuditRow(
                    row.requireId(),
                    java.util.Objects.requireNonNull(row.occurredAt()).atZone(zone),
                    row.emailAttempted(),
                    row.success(),
                    row.failureReason(),
                    row.ip());
        }
    }
}
