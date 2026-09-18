package com.caderly.caderlyhr.notifications;

import org.jspecify.annotations.Nullable;

/**
 * Every transactional email Caderly sends (PRD §17.2). One constant per row of that table.
 *
 * <p>The {@code templateKey} is the single source of three things that used to be chosen
 * independently and could therefore disagree: the Thymeleaf template under {@code
 * templates/email/}, the subject's {@code messages.properties} key, and the {@code event_type}
 * recorded on the outbox row for the Admin viewer's filter.
 */
public enum EmailEvent {
    INVITE("invite", null),
    PASSWORD_RESET("password-reset", null),
    LEAVE_REQUESTED("leave-requested", null),
    LEAVE_APPROVED("leave-approved", null),
    LEAVE_REJECTED("leave-rejected", null),
    LEAVE_CANCELLED("leave-cancelled", null),
    HOLIDAY_REMINDER("holiday-reminder", NotificationCategory.HOLIDAY_REMINDER),
    BIRTHDAY("birthday", NotificationCategory.BIRTHDAY),
    WORK_ANNIVERSARY("work-anniversary", NotificationCategory.WORK_ANNIVERSARY),
    DOCUMENT_EXPIRY("document-expiry", NotificationCategory.DOCUMENT_EXPIRY);

    private final String templateKey;
    private final @Nullable NotificationCategory category;

    EmailEvent(String templateKey, @Nullable NotificationCategory category) {
        this.templateKey = templateKey;
        this.category = category;
    }

    /** {@code null} for transactional mail no tenant may switch off. */
    public @Nullable NotificationCategory category() {
        return category;
    }

    /** Also the message-key suffix: {@code email.<key>.*} and {@code admin.notifications.event.<key>}. */
    public String templateKey() {
        return templateKey;
    }

    String templatePath() {
        return "email/" + templateKey;
    }

    String subjectKey() {
        return "email." + templateKey + ".subject";
    }
}
