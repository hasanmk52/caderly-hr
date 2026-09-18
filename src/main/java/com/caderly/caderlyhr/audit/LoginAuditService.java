package com.caderly.caderlyhr.audit;

import com.caderly.caderlyhr.audit.system.LoginAudit;
import com.caderly.caderlyhr.common.MdcKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records one login attempt (PRD §18.1, FR-11.2) and answers the (email + IP) failure count that
 * backs the lockout re-key (ADR 0017, superseding ADR 0006 decision B).
 *
 * <p>Unlike {@link EntityAuditListener}, this is called from a normal Spring Security {@code
 * @EventListener} (not from inside a JPA flush callback), so a plain repository {@code save}
 * suffices — no raw-JDBC workaround needed. {@code identity.LoginAttemptService} is the only
 * cross-module caller, per CLAUDE.md §4's cross-module-reads-through-a-facade rule.
 */
@Service
public class LoginAuditService {

    private final LoginAuditRepository logins;
    private final Clock clock;

    LoginAuditService(LoginAuditRepository logins, Clock clock) {
        this.logins = logins;
        this.clock = clock;
    }

    /**
     * IP/user-agent are passed in rather than resolved here — {@code security.LoginAttemptListener}
     * is the one place that touches {@code HttpServletRequest}, and it also needs the IP for the
     * lockout evaluation below, so resolving it twice would be redundant.
     */
    @Transactional
    public void record(
            @Nullable UUID tenantId,
            @Nullable UUID userId,
            String emailAttempted,
            boolean success,
            LoginAudit.@Nullable FailureReason failureReason,
            @Nullable String ip,
            @Nullable String userAgent) {
        String requestId = MDC.get(MdcKeys.REQUEST_ID);
        logins.save(
                new LoginAudit(
                        tenantId,
                        userId,
                        clock.instant(),
                        emailAttempted,
                        success,
                        failureReason,
                        ip,
                        userAgent,
                        requestId));
    }

    /**
     * Failed attempts for this exact (tenant, email, ip) triple in the trailing window, including
     * the row {@link #record} just wrote for the current attempt — so the 5th failure from one IP
     * is the one that observes a count of 5.
     */
    @Transactional(readOnly = true)
    public long countRecentFailures(UUID tenantId, String email, String ip, Instant windowStart) {
        return logins.countByTenantIdAndEmailAttemptedAndIpAndSuccessFalseAndOccurredAtGreaterThanEqual(
                tenantId, email, ip, windowStart);
    }
}
