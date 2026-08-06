package com.helyx.helyxhr.notifications.system;

/** Delivery state of an {@link EmailOutbox} row (PRD §21). */
public enum EmailStatus {
    /** Awaiting delivery, or awaiting a retry after a transient failure. */
    PENDING,

    /** Handed to the SMTP server successfully. Terminal. */
    SENT,

    /**
     * Gave up after exhausting the retry budget. Terminal, but the row is never deleted — an Admin
     * can inspect {@code last_error} and requeue it (retry UI lands in Phase 1.10).
     */
    FAILED
}
