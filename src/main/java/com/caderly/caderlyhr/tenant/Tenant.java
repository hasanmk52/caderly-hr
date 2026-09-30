package com.caderly.caderlyhr.tenant;

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
 * current schema. See ADR 0003. Not required by any package-cycle concern per ADR 0004; extending
 * {@code BaseEntity} is possible if a future phase wants {@code updated_at} on this table, via an
 * additive migration.
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

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    // Bitmask of weekend days: Sat=32, Sun=64 -> 96 (PRD §21); see
    // LeaveDurationCalculator.decodeWeekend for the bit convention.
    @Column(name = "weekend_days", nullable = false)
    private int weekendDays;

    @Column(name = "suspended", nullable = false)
    private boolean suspended;

    /**
     * PRD FR-9.3's per-tenant notification categories. Only the four optional ones are switchable
     * — invite, password reset and the leave lifecycle are the product working, not a category
     * anyone should be able to mute. Birthday and anniversary start off: PRD §17.2 marks both
     * "opt-in per tenant".
     */
    @Column(name = "notify_holiday_reminder", nullable = false)
    private boolean notifyHolidayReminder;

    @Column(name = "notify_document_expiry", nullable = false)
    private boolean notifyDocumentExpiry;

    @Column(name = "notify_birthday", nullable = false)
    private boolean notifyBirthday;

    @Column(name = "notify_work_anniversary", nullable = false)
    private boolean notifyWorkAnniversary;

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
        this.notifyHolidayReminder = true;
        this.notifyDocumentExpiry = true;
    }

    public Tenant(String slug, String name) {
        this.slug = slug;
        this.name = name;
        this.timezone = "UTC";
        this.locale = "en";
        this.weekendDays = 96;
        this.suspended = false;
        // Mirrors the DDL defaults in V202609151000 so a Tenant built in code and one read back
        // from a fresh insert agree.
        this.notifyHolidayReminder = true;
        this.notifyDocumentExpiry = true;
        this.notifyBirthday = false;
        this.notifyWorkAnniversary = false;
    }

    /**
     * A second business constructor for Super Admin tenant provisioning, which collects
     * timezone/weekend-days/logo up front rather than leaving them at the 2-arg
     * constructor's defaults for a later Admin edit. Mirrors that constructor's field defaults for
     * everything not taken as a parameter here, so a tenant built either way agrees with a fresh
     * insert (see the 2-arg constructor's own comment re: V202609151000).
     */
    public Tenant(String slug, String name, String timezone, int weekendDays, @Nullable String logoUrl) {
        this.slug = slug;
        this.name = name;
        this.timezone = timezone;
        this.weekendDays = weekendDays;
        this.logoUrl = logoUrl;
        this.locale = "en";
        this.suspended = false;
        this.notifyHolidayReminder = true;
        this.notifyDocumentExpiry = true;
        this.notifyBirthday = false;
        this.notifyWorkAnniversary = false;
    }

    public void suspend() {
        this.suspended = true;
    }

    public void reinstate() {
        this.suspended = false;
    }

    /** Super Admin soft-delete — no restore method, soft-delete only by design. */
    public void softDelete(Instant when) {
        this.deletedAt = when;
    }

    public void updateNotificationSettings(
            boolean holidayReminder, boolean documentExpiry, boolean birthday, boolean workAnniversary) {
        this.notifyHolidayReminder = holidayReminder;
        this.notifyDocumentExpiry = documentExpiry;
        this.notifyBirthday = birthday;
        this.notifyWorkAnniversary = workAnniversary;
    }

    public boolean isNotifyHolidayReminder() {
        return notifyHolidayReminder;
    }

    public boolean isNotifyDocumentExpiry() {
        return notifyDocumentExpiry;
    }

    public boolean isNotifyBirthday() {
        return notifyBirthday;
    }

    public boolean isNotifyWorkAnniversary() {
        return notifyWorkAnniversary;
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
