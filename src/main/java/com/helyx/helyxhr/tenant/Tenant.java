package com.helyx.helyxhr.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * The cross-tenant root (PRD §21). Deliberately does NOT extend {@code common.BaseEntity}: its
 * shape doesn't fit — no {@code tenant_id} (it IS the tenant) and no {@code updated_at} in the
 * current schema. See ADR 0003. (A former justification for this — avoiding a package cycle with
 * {@code common} — no longer applies after ADR 0004; extending {@code BaseEntity} is possible if a
 * future phase wants {@code updated_at} on this table, via an additive migration.)
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private @Nullable UUID id;

    @Column(name = "slug", nullable = false, unique = true, length = 50)
    private String slug;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "logo_url", length = 500)
    private @Nullable String logoUrl;

    @Column(name = "primary_color", length = 7)
    private @Nullable String primaryColor;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    // Bitmask of weekend days: Sat=64, Sun=32 -> 96 (PRD §21)
    @Column(name = "weekend_days", nullable = false)
    private int weekendDays;

    @Column(name = "suspended", nullable = false)
    private boolean suspended;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "deleted_at")
    private @Nullable Instant deletedAt;

    protected Tenant() {
        // JPA only
        this.slug = "";
        this.name = "";
        this.timezone = "UTC";
        this.locale = "en";
    }

    public Tenant(String slug, String name) {
        this.slug = slug;
        this.name = name;
        this.timezone = "UTC";
        this.locale = "en";
        this.weekendDays = 96;
        this.suspended = false;
    }

    public void suspend() {
        this.suspended = true;
    }

    public void reinstate() {
        this.suspended = false;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public @Nullable String getLogoUrl() {
        return logoUrl;
    }

    public @Nullable String getPrimaryColor() {
        return primaryColor;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getLocale() {
        return locale;
    }

    public int getWeekendDays() {
        return weekendDays;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public @Nullable Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getDeletedAt() {
        return deletedAt;
    }
}
