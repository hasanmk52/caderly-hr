package com.caderly.caderlyhr.notifications;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.notifications.system.EmailStatus;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the Admin delivery log at {@code /admin/notifications} (PRD FR-9.2).
 *
 * <p><strong>This class is the tenant boundary for {@code email_outbox}.</strong> That table is
 * system-scoped infrastructure — no {@code @TenantId}, no RLS policy (ADR 0005 decision B) — which
 * is exactly what lets one dispatcher drain every tenant's mail. The flip side is that nothing
 * underneath here will stop a query returning another tenant's rows: the {@code tenantId} argument
 * on every call below is the only thing that does. Neither method takes a tenant from its caller;
 * both read {@link TenantContext} directly, so a controller cannot pass the wrong one.
 */
@Service
public class NotificationAdminService {

    private static final Logger log = LoggerFactory.getLogger(NotificationAdminService.class);

    /** First paginated list in the app (UI_Guidelines §6). Enough to scan, small enough to load. */
    public static final int PAGE_SIZE = 25;

    private final EmailOutboxRepository outbox;
    private final TenantFacade tenants;
    private final Clock clock;

    NotificationAdminService(EmailOutboxRepository outbox, TenantFacade tenants, Clock clock) {
        this.outbox = outbox;
        this.tenants = tenants;
        this.clock = clock;
    }

    /**
     * One page of this tenant's mail, newest first.
     *
     * <p>{@code from}/{@code to} are inclusive calendar days in the tenant's own zone
     * (UI_Guidelines §11), so "to = today" includes everything queued today rather than stopping
     * at midnight UTC.
     */
    @Transactional(readOnly = true)
    public Page<OutboxRow> list(
            @Nullable EmailStatus status, @Nullable LocalDate from, @Nullable LocalDate to, int page) {
        UUID tenantId = TenantContext.require();
        ZoneId zone = tenants.currentTimezone();
        Instant fromInstant = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        return outbox
                .findAll(
                        forTenant(tenantId).and(withStatus(status)).and(queuedBetween(fromInstant, toInstant)),
                        PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(row -> OutboxRow.of(row, zone));
    }

    /** The isolation boundary. Never optional, never taken from the caller. */
    private static Specification<EmailOutbox> forTenant(UUID tenantId) {
        return (root, query, builder) -> builder.equal(root.get("tenantId"), tenantId);
    }

    private static Specification<EmailOutbox> withStatus(@Nullable EmailStatus status) {
        return (root, query, builder) ->
                status == null ? builder.conjunction() : builder.equal(root.get("status"), status);
    }

    private static Specification<EmailOutbox> queuedBetween(
            @Nullable Instant from, @Nullable Instant to) {
        return (root, query, builder) -> {
            Predicate predicate = builder.conjunction();
            if (from != null) {
                predicate = builder.and(predicate, builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicate = builder.and(predicate, builder.lessThan(root.get("createdAt"), to));
            }
            return predicate;
        };
    }

    /**
     * Puts a FAILED row back in the queue for the dispatcher to pick up on its next poll.
     *
     * <p>A row belonging to another tenant is reported as <em>not found</em>, not as forbidden:
     * "you may not touch this" confirms it exists, and the id is guessable in a way an employee
     * record is not.
     */
    @Transactional
    public void requeue(UUID rowId) {
        UUID tenantId = TenantContext.require();
        EmailOutbox row =
                outbox.findById(rowId)
                        .filter(candidate -> tenantId.equals(candidate.tenantId()))
                        .orElseThrow(() -> new NotFoundException("EMAIL_NOT_FOUND", "Email not found"));
        if (row.status() != EmailStatus.FAILED) {
            throw new ValidationException(
                    "EMAIL_NOT_RETRYABLE", "Only an email that failed permanently can be retried");
        }
        row.requeue(clock.instant());
        outbox.save(row);
        log.info("Requeued email {} for tenant {}", rowId, tenantId);
    }

    /** What the delivery-log table renders. Never carries {@code bodyHtml} — it may hold a token. */
    public record OutboxRow(
            UUID id,
            ZonedDateTime createdAt,
            String toEmail,
            String subject,
            @Nullable EmailEvent event,
            EmailStatus status,
            int attempts,
            @Nullable String lastError) {

        static OutboxRow of(EmailOutbox row, ZoneId zone) {
            return new OutboxRow(
                    row.requireId(),
                    // createdAt is only null before the insert; these rows are all persisted.
                    java.util.Objects.requireNonNull(row.getCreatedAt()).atZone(zone),
                    row.toEmail(),
                    row.subject(),
                    parseEvent(row.eventType()),
                    row.status(),
                    row.attempts(),
                    row.lastError());
        }

        /**
         * Null for rows written before sub-phase 1.10's catalogue, and for a value this build does
         * not recognise — a downgrade must render the log, not throw on it.
         */
        private static @Nullable EmailEvent parseEvent(@Nullable String eventType) {
            if (eventType == null) {
                return null;
            }
            try {
                return EmailEvent.valueOf(eventType);
            } catch (IllegalArgumentException unknownToThisBuild) {
                return null;
            }
        }

        public boolean retryable() {
            return status == EmailStatus.FAILED;
        }
    }
}
