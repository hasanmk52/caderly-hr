package com.caderly.caderlyhr.notifications;

import com.caderly.caderlyhr.tenant.TenantFacade.NotificationSettings;

/**
 * The notification categories an Admin can switch off per tenant (PRD FR-9.3).
 *
 * <p>Deliberately smaller than {@link EmailEvent}: invite, password reset and the leave lifecycle
 * have no category, because they are not optional extras — muting them would break the flows they
 * belong to (an invite nobody receives is an account nobody can activate).
 */
public enum NotificationCategory {
    HOLIDAY_REMINDER,
    DOCUMENT_EXPIRY,
    BIRTHDAY,
    WORK_ANNIVERSARY;

    boolean enabledIn(NotificationSettings settings) {
        return switch (this) {
            case HOLIDAY_REMINDER -> settings.holidayReminder();
            case DOCUMENT_EXPIRY -> settings.documentExpiry();
            case BIRTHDAY -> settings.birthday();
            case WORK_ANNIVERSARY -> settings.workAnniversary();
        };
    }
}
