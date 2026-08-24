package com.caderly.caderlyhr.notifications;

import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way to send email in Caderly (CLAUDE.md §6a). Callers record the *intent* to send; an
 * {@code EmailDispatcher} performs the actual SMTP call later, out of band.
 *
 * <p>There is deliberately no {@code send()} here. A {@code mailSender.send()} in a request path
 * either blocks the user on a third party or, worse, succeeds after its own transaction rolled
 * back — announcing something that did not happen.
 */
@Service
public class EmailOutboxService {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxService.class);

    private final EmailOutboxRepository outbox;
    private final Clock clock;

    EmailOutboxService(EmailOutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
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
     * @param tenantId owning tenant, or {@code null} for system mail that belongs to none
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(@Nullable UUID tenantId, String toEmail, String subject, String bodyHtml) {
        EmailOutbox row = new EmailOutbox(tenantId, toEmail, subject, bodyHtml, clock.instant());
        UUID id = outbox.save(row).requireId();
        // The recipient address is not a secret, but the body may contain a one-time token,
        // so it is never logged (CLAUDE.md §6 A09).
        log.info("Queued email {} to {} (subject: {})", id, toEmail, subject);
        return id;
    }
}
