package com.caderly.caderlyhr.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.notifications.system.EmailDispatcher;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.notifications.system.EmailStatus;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox all the way onto the wire: enqueue, dispatch, and read the message back out of a
 * real SMTP server. Everything else stubs {@link org.springframework.mail.javamail.JavaMailSender}
 * to force failures, so this is the test that proves a working message is actually well-formed
 * and deliverable.
 *
 * <p>GreenMail runs in-process rather than as a MailHog container: same coverage of this path,
 * no Docker image to pull, and no port collision with a Mailpit a developer has running locally.
 */
class EmailSmtpDeliveryTest extends TenantIsolationTestBase {

    private static final int SMTP_PORT = 3125;

    private static GreenMail greenMail;

    @Autowired private EmailOutboxService outboxService;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private EmailDispatcher dispatcher;
    @Autowired private TransactionTemplate transactions;

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> SMTP_PORT);
    }

    @BeforeAll
    static void startSmtpServer() {
        greenMail = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
    }

    @AfterAll
    static void stopSmtpServer() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @Test
    void enqueuedEmail_isDeliveredToTheSmtpServerWithItsSubjectAndBody() throws Exception {
        String recipient = "recipient-" + UUID.randomUUID() + "@example.test";
        String subject = "Welcome to Caderly";
        String body = "<p>Set your password</p>";

        UUID rowId =
                asTenant(
                        tenantA,
                        () ->
                                transactions.execute(
                                        status -> outboxService.enqueue(tenantA, recipient, subject, body)));

        dispatcher.dispatchPending();

        assertThat(greenMail.waitForIncomingEmail(5_000, 1)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        MimeMessage delivered =
                java.util.Arrays.stream(received)
                        .filter(message -> hasRecipient(message, recipient))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No message delivered to " + recipient));

        assertThat(delivered.getSubject()).isEqualTo(subject);
        assertThat(GreenMailUtil.getBody(delivered)).contains("Set your password");
        assertThat(delivered.getFrom()[0].toString()).isEqualTo("no-reply@caderly.test");

        EmailOutbox row =
                TenantContext.runAsSystem(
                        "test: read outbox row", () -> outbox.findById(rowId).orElseThrow());
        assertThat(row.status()).isEqualTo(EmailStatus.SENT);
        assertThat(row.sentAt()).isNotNull();
    }

    private static boolean hasRecipient(MimeMessage message, String recipient) {
        try {
            return java.util.Arrays.stream(message.getAllRecipients())
                    .anyMatch(address -> address.toString().contains(recipient));
        } catch (Exception exception) {
            return false;
        }
    }
}
