package com.helyx.helyxhr.notifications.system;

import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers a single outbox row, in its own transaction.
 *
 * <p>Separate from {@link EmailDispatcher} on purpose: Spring's {@code @Transactional} is
 * proxy-based, so a self-invoked method on the dispatcher would run with no transaction at all and
 * the status updates would never commit. Splitting the loop from the unit of work makes the
 * boundary a compile-time fact rather than something to remember.
 *
 * <p>One transaction per row also means a poison message cannot roll back the successful
 * deliveries that happened alongside it.
 */
@Component
class EmailDelivery {

    private static final Logger log = LoggerFactory.getLogger(EmailDelivery.class);

    private final EmailOutboxRepository outbox;
    private final JavaMailSender mailSender;
    private final Clock clock;
    private final String fromAddress;

    EmailDelivery(
            EmailOutboxRepository outbox,
            JavaMailSender mailSender,
            Clock clock,
            @Value("${helyx.email.from}") String fromAddress) {
        this.outbox = outbox;
        this.mailSender = mailSender;
        this.clock = clock;
        this.fromAddress = fromAddress;
    }

    /**
     * Attempts one delivery. Never throws for a send failure — the whole point is that the failure
     * is recorded durably rather than propagated and lost.
     *
     * <p>Delivery is at-least-once: a crash between a successful SMTP handoff and this
     * transaction's commit leaves the row {@code PENDING}, and it will be sent again. That is the
     * correct trade for an outbox — a duplicate invite is recoverable, a silently dropped one is
     * not.
     */
    @Transactional
    void attemptDelivery(UUID rowId) {
        EmailOutbox row = outbox.findById(rowId).orElse(null);
        if (row == null || row.status() != EmailStatus.PENDING) {
            // Already handled by an earlier pass, or requeued and picked up elsewhere.
            return;
        }

        try {
            mailSender.send(toMimeMessage(row));
            row.markSent(clock.instant());
            log.info("Delivered email {} to {}", rowId, row.toEmail());
        } catch (Exception exception) {
            boolean willRetry = row.markAttemptFailed(describe(exception), clock.instant());
            if (willRetry) {
                log.warn(
                        "Email {} to {} failed on attempt {}; retrying at {}",
                        rowId,
                        row.toEmail(),
                        row.attempts(),
                        row.nextAttemptAt(),
                        exception);
            } else {
                log.error(
                        "Email {} to {} FAILED permanently after {} attempts; row retained for retry",
                        rowId,
                        row.toEmail(),
                        row.attempts(),
                        exception);
            }
        }
        outbox.save(row);
    }

    private MimeMessage toMimeMessage(EmailOutbox row) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(row.toEmail());
        helper.setSubject(row.subject());
        helper.setText(row.bodyHtml(), true);
        return message;
    }

    /** Exception class plus message — enough to diagnose, with no stack trace bloating the row. */
    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
