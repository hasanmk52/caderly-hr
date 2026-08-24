package com.caderly.caderlyhr.notifications.system;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Plain {@link JpaRepository}, not {@code TenantAwareRepository}: {@link EmailOutbox} is
 * system-scoped (ADR 0005 decision B), so there is no tenant restriction to inherit and the
 * dispatcher sees every tenant's mail in one query.
 */
@Repository
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, UUID> {

    /** Rows due for a delivery attempt, oldest first. Backed by idx_email_outbox_due. */
    List<EmailOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            EmailStatus status, Instant dueBefore, Limit limit);

    List<EmailOutbox> findByStatusOrderByCreatedAtDesc(EmailStatus status);

    List<EmailOutbox> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
