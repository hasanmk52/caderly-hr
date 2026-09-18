package com.caderly.caderlyhr.audit.system;

import com.caderly.caderlyhr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One recorded write (PRD §18.1, FR-11.1): before/after JSON for a single entity, at a single
 * point in time.
 *
 * <p>Extends {@link BaseEntity}, <strong>not</strong> {@code TenantAwareEntity}. This is
 * system-scoped infrastructure — see ADR 0005 decision B and ADR 0017. {@link #tenantId} is a
 * reference for Admin-viewer filtering, not a tenancy discriminator: {@code
 * audit.AuditAdminService} is the tenant boundary, the same role {@code
 * notifications.NotificationAdminService} plays for {@code email_outbox}.
 *
 * <p>Rows are written by {@code audit.EntityAuditListener} via a raw JDBC insert, never through this
 * entity's own repository — see that class's Javadoc for why a normal {@code EntityManager}
 * persist from inside a JPA flush callback is unsafe. This class exists purely as the read model
 * the Admin viewer queries; it has no persist-capable constructor.
 */
@Entity
@Table(name = "audit_entry")
public class AuditEntry extends BaseEntity {

    /** FR-11.1 / PRD §18.2's three lifecycle callbacks: create, update, delete. */
    public enum Action {
        CREATE,
        UPDATE,
        DELETE
    }

    @Column(name = "tenant_id")
    private @Nullable UUID tenantId;

    @Column(name = "actor_user_id")
    private @Nullable UUID actorUserId;

    @Column(name = "actor_role", length = 20)
    private @Nullable String actorRole;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id", length = 50)
    private @Nullable String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private Action action;

    @Column(name = "before_json", columnDefinition = "jsonb")
    private @Nullable String beforeJson;

    @Column(name = "after_json", columnDefinition = "jsonb")
    private @Nullable String afterJson;

    @Column(name = "ip", length = 45)
    private @Nullable String ip;

    @Column(name = "user_agent", length = 500)
    private @Nullable String userAgent;

    @Column(name = "request_id", length = 50)
    private @Nullable String requestId;

    protected AuditEntry() {}

    public @Nullable UUID tenantId() {
        return tenantId;
    }

    public @Nullable UUID actorUserId() {
        return actorUserId;
    }

    public @Nullable String actorRole() {
        return actorRole;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String entityType() {
        return entityType;
    }

    public @Nullable String entityId() {
        return entityId;
    }

    public Action action() {
        return action;
    }

    public @Nullable String beforeJson() {
        return beforeJson;
    }

    public @Nullable String afterJson() {
        return afterJson;
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
