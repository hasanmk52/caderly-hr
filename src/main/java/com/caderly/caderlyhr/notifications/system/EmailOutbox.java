package com.caderly.caderlyhr.notifications.system;

import com.caderly.caderlyhr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An email that has been *decided on* but not yet sent (CLAUDE.md §6a).
 *
 * <p>The row is written in the same transaction as the business action that triggered it, so the
 * decision to send and the record of that decision commit or roll back together. A process kill
 * between commit and delivery loses nothing: the row is still {@code PENDING} on restart.
 *
 * <p>Extends {@link BaseEntity}, <strong>not</strong> {@code TenantAwareEntity}. This is
 * system-scoped infrastructure — see ADR 0005 decision B and the migration header. {@link
 * #tenantId} is a reference for branding and filtering, not a tenancy discriminator.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox extends BaseEntity {

    /** PRD §17.3 / CLAUDE.md §6a: three attempts, backing off 30s then 2m then 10m. */
    static final List<Duration> RETRY_BACKOFF =
            List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));

    static final int MAX_ATTEMPTS = 3;

    /**
     * The column is {@code text} and imposes no limit of its own; this one exists so a driver or
     * library exception with a megabyte-long message cannot bloat the row.
     */
    private static final int MAX_ERROR_LENGTH = 4000;

    @Column(name = "tenant_id")
    private @Nullable UUID tenantId;

    @Column(name = "to_email", nullable = false)
    private String toEmail;

    @Column(name = "subject", nullable = false, length = 500)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "text")
    private String bodyHtml;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmailStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private @Nullable String lastError;

    @Column(name = "sent_at")
    private @Nullable Instant sentAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    protected EmailOutbox() {}

    /**
     * Public because {@code EmailOutboxService} in the parent package is the supported way to
     * create one. Creating a PENDING row is harmless; it is the state <em>transitions</em>
     * (markSent, markAttemptFailed) that stay package-private, so only the dispatcher can move a
     * row through its lifecycle.
     */
    public EmailOutbox(
            @Nullable UUID tenantId, String toEmail, String subject, String bodyHtml, Instant now) {
        this.tenantId = tenantId;
        this.toEmail = toEmail;
        this.subject = subject;
        this.bodyHtml = bodyHtml;
        this.status = EmailStatus.PENDING;
        this.nextAttemptAt = now;
    }

    void markSent(Instant now) {
        this.status = EmailStatus.SENT;
        this.sentAt = now;
        this.attempts++;
        this.lastError = null;
    }

    /**
     * Records a failed send and decides whether to retry.
     *
     * <p>Returns {@code true} if the row was rescheduled, {@code false} if the retry budget is
     * spent and it is now {@code FAILED}. Either way the row survives — losing the intent is the
     * one thing an outbox may never do.
     */
    boolean markAttemptFailed(String error, Instant now) {
        this.attempts++;
        this.lastError = truncate(error);
        if (attempts >= MAX_ATTEMPTS) {
            this.status = EmailStatus.FAILED;
            return false;
        }
        // attempts is 1-based after the increment, and the first failure should wait
        // RETRY_BACKOFF[0], so index by attempts - 1.
        this.nextAttemptAt = now.plus(RETRY_BACKOFF.get(attempts - 1));
        return true;
    }

    private static @Nullable String truncate(@Nullable String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public @Nullable UUID tenantId() {
        return tenantId;
    }

    public String toEmail() {
        return toEmail;
    }

    public String subject() {
        return subject;
    }

    public String bodyHtml() {
        return bodyHtml;
    }

    public EmailStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    public @Nullable Instant sentAt() {
        return sentAt;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }
}
