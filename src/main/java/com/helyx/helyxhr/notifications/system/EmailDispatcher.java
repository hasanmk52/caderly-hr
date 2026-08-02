package com.helyx.helyxhr.notifications.system;

import com.helyx.helyxhr.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the email outbox (CLAUDE.md §6a rule 2).
 *
 * <p>Polls rather than listens, deliberately: a poll recovers from a crash with no extra
 * machinery, because the queue is the table.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final Limit BATCH_SIZE = Limit.of(50);

    private final EmailOutboxRepository outbox;
    private final EmailDelivery delivery;
    private final Clock clock;
    private final boolean enabled;

    EmailDispatcher(
            EmailOutboxRepository outbox,
            EmailDelivery delivery,
            Clock clock,
            @Value("${helyx.email.dispatcher.enabled:true}") boolean enabled) {
        this.outbox = outbox;
        this.delivery = delivery;
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * Scheduled entry point. Tests set {@code helyx.email.dispatcher.enabled=false} and call
     * {@link #dispatchPending()} at a known moment rather than racing the scheduler; the bean
     * still exists so they can.
     */
    @Scheduled(
            fixedDelayString = "${helyx.email.dispatcher.poll-interval-ms:30000}",
            initialDelayString = "${helyx.email.dispatcher.poll-interval-ms:30000}")
    void scheduledDispatch() {
        if (!enabled) {
            return;
        }
        dispatchPending();
    }

    /**
     * Delivers every currently-due row. Returns how many were attempted, so tests and the future
     * Admin retry UI can assert on progress.
     *
     * <p>The {@code runAsSystem} wrapper is not optional and not cosmetic. {@code
     * TenantSessionVariableListener} is registered on the <em>transaction manager</em>, so its
     * {@code afterBegin} runs for every transaction in the application and calls {@code
     * TenantContext.require()} unless system mode is set. On this scheduler thread there is no
     * tenant, so without the wrapper every transaction below would die at begin — before a single
     * outbox row was read, and regardless of the fact that {@code email_outbox} itself is
     * system-scoped. (ADR 0005 records this; an earlier draft of that ADR got it wrong.)
     */
    public int dispatchPending() {
        return TenantContext.runAsSystem(
                "email outbox dispatch",
                () -> {
                    List<UUID> due = findDueRowIds();
                    if (due.isEmpty()) {
                        return 0;
                    }
                    log.debug("Dispatching {} due email(s)", due.size());
                    due.forEach(delivery::attemptDelivery);
                    return due.size();
                });
    }

    /**
     * Deliberately not annotated {@code @Transactional}: it is called from {@link
     * #dispatchPending()} on this same bean, so the proxy would be bypassed and the annotation
     * would be a lie. Spring Data's own read-only transaction around the query is exactly what is
     * wanted here anyway — short-lived, and released before the first SMTP round trip.
     */
    List<UUID> findDueRowIds() {
        return outbox
                .findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        EmailStatus.PENDING, clock.instant(), BATCH_SIZE)
                .stream()
                .map(row -> {
                    UUID id = row.getId();
                    if (id == null) {
                        throw new IllegalStateException("Persisted outbox row has no id");
                    }
                    return id;
                })
                .toList();
    }
}
