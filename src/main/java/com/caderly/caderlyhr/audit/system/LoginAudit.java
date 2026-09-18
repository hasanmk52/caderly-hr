package com.caderly.caderlyhr.audit.system;

import com.caderly.caderlyhr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One login attempt, success or failure (PRD §18.1, FR-11.2).
 *
 * <p>Extends {@link BaseEntity}, <strong>not</strong> {@code TenantAwareEntity}. Same
 * system-scoped-infrastructure category as {@link AuditEntry} and {@code email_outbox} — ADR
 * 0005 decision B, ADR 0017 — because an attempt against an unknown email has no {@code AppUser}
 * row to scope against. {@link #tenantId} is a nullable reference; {@code
 * audit.LoginAuditAdminService} is the tenant boundary for the Admin viewer.
 *
 * <p>Unlike {@link AuditEntry}, this is written from a normal Spring Security event listener, not
 * from inside a JPA flush callback, so a plain repository {@code save(...)} is safe here — no
 * raw-JDBC workaround needed.
 */
@Entity
@Table(name = "login_audit")
public class LoginAudit extends BaseEntity {

    /** PRD §18.1's {@code failure_reason} values; {@code null} on a successful attempt. */
    public enum FailureReason {
        UNKNOWN_EMAIL,
        BAD_CREDENTIALS,
        ACCOUNT_LOCKED,
        ACCOUNT_DISABLED_OR_INVITED
    }

    @Column(name = "tenant_id")
    private @Nullable UUID tenantId;

    @Column(name = "user_id")
    private @Nullable UUID userId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "email_attempted", length = 255)
    private @Nullable String emailAttempted;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "failure_reason", length = 100)
    private @Nullable String failureReason;

    @Column(name = "ip", length = 45)
    private @Nullable String ip;

    @Column(name = "user_agent", length = 500)
    private @Nullable String userAgent;

    @Column(name = "request_id", length = 50)
    private @Nullable String requestId;

    protected LoginAudit() {}

    public LoginAudit(
            @Nullable UUID tenantId,
            @Nullable UUID userId,
            Instant occurredAt,
            @Nullable String emailAttempted,
            boolean success,
            @Nullable FailureReason failureReason,
            @Nullable String ip,
            @Nullable String userAgent,
            @Nullable String requestId) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.occurredAt = occurredAt;
        this.emailAttempted = emailAttempted;
        this.success = success;
        this.failureReason = failureReason == null ? null : failureReason.name();
        this.ip = ip;
        this.userAgent = userAgent;
        this.requestId = requestId;
    }

    public @Nullable UUID tenantId() {
        return tenantId;
    }

    public @Nullable UUID userId() {
        return userId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public @Nullable String emailAttempted() {
        return emailAttempted;
    }

    public boolean success() {
        return success;
    }

    public @Nullable String failureReason() {
        return failureReason;
    }

    public @Nullable String ip() {
        return ip;
    }

    public @Nullable String userAgent() {
        return userAgent;
    }

    public @Nullable String requestId() {
        return requestId;
    }
}
