package com.caderly.caderlyhr.notifications.system;

/** Delivery state of an {@link EmailOutbox} row (PRD §21). */
public enum EmailStatus {
    /** Awaiting delivery, or awaiting a retry after a transient failure. */
    PENDING,

    /** Handed to the SMTP server successfully. Terminal. */
    SENT,

    /**
     * Gave up after exhausting the retry budget. Terminal, but the row is never deleted — an Admin
     * can inspect {@code last_error} and requeue it via {@link
     * com.caderly.caderlyhr.notifications.NotificationAdminService#requeue}.
     */
    FAILED
}
