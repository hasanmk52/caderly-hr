package com.caderly.caderlyhr.notifications;

import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way to send email in Caderly (CLAUDE.md §6a). Callers record the <em>intent</em> to
 * send; an {@code EmailDispatcher} performs the actual SMTP call later, out of band.
 *
 * <p>There is deliberately no {@code send()} here. A {@code mailSender.send()} in a request path
 * either blocks the user on a third party or, worse, succeeds after its own transaction rolled
 * back — announcing something that did not happen. {@code ArchitectureTest} confines {@code
 * JavaMailSender} to {@code notifications.system} so that stays true by construction.
 *
 * <p>Nor is there a way to pass a hand-built subject and body: an event names a template, and the
 * template is the only place email HTML exists (sub-phase 1.10 — before it, two modules each kept
 * their own copy of the same chrome).
 */
@Service
public class EmailOutboxService {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxService.class);

    private final EmailOutboxRepository outbox;
    private final EmailTemplateService templates;
    private final TenantFacade tenants;
    private final MessageSource messages;
    private final Clock clock;

    EmailOutboxService(
            EmailOutboxRepository outbox,
            EmailTemplateService templates,
            TenantFacade tenants,
            MessageSource messages,
            Clock clock) {
        this.outbox = outbox;
        this.templates = templates;
        this.tenants = tenants;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * Records an email to be delivered, in the caller's transaction.
     *
     * <p>{@code MANDATORY} propagation is the enforcement mechanism for CLAUDE.md §6a rule 1: this
     * throws rather than quietly opening its own transaction if no caller transaction exists.
     * Without it, an enqueue could commit while the business action that justified it rolled back,
     * and the user would get an invite to an account that does not exist.
     *
     * @param model template variables; {@code tenantName} and {@code logoUrl} are added for you
     * @param subjectArgs positional arguments for the event's {@code email.<key>.subject} message
     * @return the new row's id, or empty when this tenant has the event's category switched off
     *     (PRD FR-9.3) — a disabled category writes no row at all, so nothing is left PENDING for
     *     a later re-enable to flush out
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> enqueue(
            EmailEvent event, String toEmail, Map<String, Object> model, Object... subjectArgs) {
        UUID tenantId = TenantContext.get().orElse(null);
        if (!isEnabled(event)) {
            log.debug("Skipping {} to {}: category disabled for tenant {}", event, toEmail, tenantId);
            return Optional.empty();
        }
        String subject =
                messages.getMessage(
                        event.subjectKey(), subjectArgs, event.name(), EmailTemplateService.EMAIL_LOCALE);
        EmailOutbox row =
                new EmailOutbox(
                        tenantId, event.name(), toEmail, subject, templates.render(event, model), clock.instant());
        UUID id = outbox.save(row).requireId();
        // The recipient address is not a secret, but the body may contain a one-time token,
        // so it is never logged (CLAUDE.md §6 A09).
        log.info("Queued {} email {} to {} (subject: {})", event, id, toEmail, subject);
        return Optional.of(id);
    }

    /**
     * Whether this recipient was already queued for this event since {@code since} — the daily
     * sweep jobs' guard against a restart across the cron minute re-sending the whole batch.
     */
    @Transactional(readOnly = true)
    public boolean alreadyQueuedSince(EmailEvent event, String toEmail, Instant since) {
        UUID tenantId = TenantContext.require();
        return outbox.existsByTenantIdAndEventTypeAndToEmailAndCreatedAtGreaterThanEqual(
                tenantId, event.name(), toEmail, since);
    }

    /**
     * System mail (no tenant) is never suppressed: there is no tenant whose preference it could be
     * honouring, and the only mail in that category is operational.
     */
    private boolean isEnabled(EmailEvent event) {
        @Nullable NotificationCategory category = event.category();
        if (category == null || TenantContext.get().isEmpty()) {
            return true;
        }
        return category.enabledIn(tenants.currentNotificationSettings());
    }
}
