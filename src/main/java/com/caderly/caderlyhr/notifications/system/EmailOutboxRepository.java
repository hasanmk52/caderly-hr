package com.caderly.caderlyhr.notifications.system;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Plain {@link JpaRepository}, not {@code TenantAwareRepository}: {@link EmailOutbox} is
 * system-scoped (ADR 0005 decision B), so there is no tenant restriction to inherit and the
 * dispatcher sees every tenant's mail in one query.
 *
 * <p>{@link JpaSpecificationExecutor} backs the Admin delivery log, whose status and date filters
 * are each independently optional. Written as a {@code @Query} with {@code (:p IS NULL OR ...)}
 * guards it failed at runtime — Postgres cannot infer a bare untyped parameter's type in {@code ?
 * IS NULL} and rejects the statement. A Specification simply omits the predicate instead.
 * {@code NotificationAdminService} is the only permitted caller: it supplies the tenant predicate
 * that this table has no RLS policy to supply for it.
 */
@Repository
public interface EmailOutboxRepository
        extends JpaRepository<EmailOutbox, UUID>, JpaSpecificationExecutor<EmailOutbox> {

    /** Rows due for a delivery attempt, oldest first. Backed by idx_email_outbox_due. */
    List<EmailOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            EmailStatus status, Instant dueBefore, Limit limit);

    List<EmailOutbox> findByStatusOrderByCreatedAtDesc(EmailStatus status);

    List<EmailOutbox> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /**
     * The daily sweep jobs' idempotency probe. A restart across the cron minute would otherwise
     * re-send every birthday and holiday reminder that day; this asks whether we already decided
     * to. Backed by idx_email_outbox_dedupe.
     */
    boolean existsByTenantIdAndEventTypeAndToEmailAndCreatedAtGreaterThanEqual(
            UUID tenantId, String eventType, String toEmail, Instant since);
}
