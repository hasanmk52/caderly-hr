package com.caderly.caderlyhr.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.caderly.caderlyhr.notifications.system.EmailDispatcher;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.notifications.system.EmailStatus;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The durability contract from CLAUDE.md §6a: an enqueued email commits with its business action,
 * is retried on the specified backoff, and is never lost — not even when delivery fails for good.
 *
 * <p>Backoff is exercised at its real 30s / 2m durations by advancing a {@link MutableClock}
 * rather than by rewriting rows, so the schedule itself is under test and nothing sleeps.
 */
@Import(MutableClockConfiguration.class)
class EmailOutboxTest extends TenantIsolationTestBase {

    @Autowired private EmailOutboxService outboxService;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private EmailDispatcher dispatcher;
    @Autowired private TransactionTemplate transactions;
    @Autowired private MutableClock clock;

    @MockitoBean private JavaMailSender mailSender;

    @BeforeEach
    void stubMailSender() {
        // A real JavaMailSenderImpl would dial localhost:3025; the SMTP wire path gets its own
        // GreenMail-backed test. Here the sender is a seam for forcing failures.
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new java.util.Properties())));
        doNothing().when(mailSender).send(any(MimeMessage.class));
    }

    @BeforeEach
    void clearOutbox() {
        // email_outbox is system-scoped infrastructure (ADR 0005) with no per-tenant slug to keep
        // rows apart, and the Testcontainers Postgres instance is reused (.withReuse(true)) across
        // every local test run. Without this, PENDING rows left behind by an earlier run become
        // "due" the moment any test here advances the shared MutableClock, get swept into an
        // unrelated dispatchPending() call, and inflate/desync the mailSender.send() call count.
        TenantContext.runAsSystem(
                "test: clear email outbox",
                () -> {
                    outbox.deleteAll();
                    return null;
                });
    }

    @Test
    void enqueue_whenNoTransactionActive_throwsRatherThanSilentlyStartingOne() {
        // MANDATORY propagation is what makes CLAUDE.md §6a rule 1 structural rather than a
        // convention: an enqueue that opened its own transaction could commit an invite email
        // for a user creation that then rolled back.
        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () -> outboxService.enqueue(tenantA, "x@example.test", "s", "b")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void enqueue_whenCallerTransactionRollsBack_leavesNoOutboxRow() {
        String recipient = "rollback-" + UUID.randomUUID() + "@example.test";

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                transactions.execute(
                                                        status -> {
                                                            outboxService.enqueue(
                                                                    tenantA, recipient, "Subject", "<p>Body</p>");
                                                            throw new IllegalStateException("business failure");
                                                        })))
                .isInstanceOf(IllegalStateException.class);

        assertThat(allRecipients()).doesNotContain(recipient);
    }

    @Test
    void enqueuedRow_beforeAnyDispatch_isDurablyPendingAndDeliveredOnTheNextPoll() {
        // The DoD's SIGKILL scenario as something a test can actually assert: the row is
        // committed and PENDING with no dispatcher involvement, so a process dying here loses
        // nothing. A restart is simply the next dispatchPending() call.
        UUID rowId = enqueue("durable-" + UUID.randomUUID() + "@example.test");

        EmailOutbox beforeDispatch = findRow(rowId);
        assertThat(beforeDispatch.status()).isEqualTo(EmailStatus.PENDING);
        assertThat(beforeDispatch.attempts()).isZero();
        assertThat(beforeDispatch.sentAt()).isNull();

        dispatcher.dispatchPending();

        EmailOutbox afterDispatch = findRow(rowId);
        assertThat(afterDispatch.status()).isEqualTo(EmailStatus.SENT);
        assertThat(afterDispatch.attempts()).isEqualTo(1);
        assertThat(afterDispatch.sentAt()).isEqualTo(clock.instant());
    }

    @Test
    void dispatchPending_whenSenderFailsTwice_succeedsOnThirdAttemptWithAttemptsThree() {
        doThrow(new MailSendException("smtp down"))
                .doThrow(new MailSendException("smtp still down"))
                .doNothing()
                .when(mailSender)
                .send(any(MimeMessage.class));

        UUID rowId = enqueue("retry-" + UUID.randomUUID() + "@example.test");

        dispatcher.dispatchPending();
        assertThat(findRow(rowId).status()).isEqualTo(EmailStatus.PENDING);
        assertThat(findRow(rowId).attempts()).isEqualTo(1);
        assertThat(findRow(rowId).lastError()).contains("smtp down");

        clock.advance(Duration.ofSeconds(31));
        dispatcher.dispatchPending();
        assertThat(findRow(rowId).status()).isEqualTo(EmailStatus.PENDING);
        assertThat(findRow(rowId).attempts()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(3));
        dispatcher.dispatchPending();

        EmailOutbox delivered = findRow(rowId);
        assertThat(delivered.status()).isEqualTo(EmailStatus.SENT);
        assertThat(delivered.attempts()).isEqualTo(3);
        assertThat(delivered.lastError()).isNull();
        verify(mailSender, times(3)).send(any(MimeMessage.class));
    }

    @Test
    void dispatchPending_whenSenderAlwaysFails_marksFailedAfterThreeAttemptsAndKeepsTheRow() {
        doThrow(new MailSendException("permanently broken"))
                .when(mailSender)
                .send(any(MimeMessage.class));

        UUID rowId = enqueue("doomed-" + UUID.randomUUID() + "@example.test");

        dispatcher.dispatchPending();
        clock.advance(Duration.ofSeconds(31));
        dispatcher.dispatchPending();
        clock.advance(Duration.ofMinutes(3));
        dispatcher.dispatchPending();

        EmailOutbox failed = findRow(rowId);
        assertThat(failed.status()).isEqualTo(EmailStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(failed.lastError()).contains("permanently broken");
        // Never lose the intent row (CLAUDE.md §6a rule 3) — Phase 1.10's Admin UI retries it.
        assertThat(findRow(rowId)).isNotNull();

        // And a FAILED row is not picked up again by ordinary polling.
        clock.advance(Duration.ofHours(1));
        assertThat(dispatcher.dispatchPending()).isZero();
        verify(mailSender, times(3)).send(any(MimeMessage.class));
    }

    @Test
    void dispatchPending_whenRowIsStillInsideItsBackoffWindow_doesNotConsumeAnAttempt() {
        doThrow(new MailSendException("fail once")).when(mailSender).send(any(MimeMessage.class));
        UUID rowId = enqueue("backoff-" + UUID.randomUUID() + "@example.test");

        dispatcher.dispatchPending();
        assertThat(findRow(rowId).nextAttemptAt())
                .isEqualTo(clock.instant().plus(Duration.ofSeconds(30)));

        // Second poll 10 seconds later — inside the window, so nothing should happen.
        clock.advance(Duration.ofSeconds(10));
        assertThat(dispatcher.dispatchPending()).isZero();

        assertThat(findRow(rowId).attempts()).isEqualTo(1);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void dispatchPending_runsWithNoTenantInContext() {
        // Regression guard for the ADR 0005 correction: TenantSessionVariableListener is
        // registered on the transaction manager, so every transaction demands a tenant unless
        // system mode is set. Without runAsSystem inside the dispatcher this throws
        // CannotCreateTransactionException before a single row is read.
        enqueue("no-context-" + UUID.randomUUID() + "@example.test");
        TenantContext.clear();

        assertThat(TenantContext.get()).isEmpty();
        assertThat(dispatcher.dispatchPending()).isPositive();
    }

    @Test
    void enqueue_forSystemMail_acceptsNullTenant() {
        UUID rowId =
                asTenant(
                        tenantA,
                        () ->
                                transactions.execute(
                                        status ->
                                                outboxService.enqueue(
                                                        null, "ops@example.test", "System", "<p>x</p>")));

        assertThat(findRow(rowId).tenantId()).isNull();
    }

    @Test
    void outboxRows_areVisibleAcrossTenants_becauseTheTableIsSystemScoped() {
        // The deliberate counterpart to every other isolation test in this codebase: this table
        // is infrastructure, so one dispatcher drains every tenant's mail (ADR 0005 decision B).
        // Any Admin viewer over it must therefore filter by tenant_id itself.
        String toA = "a-" + UUID.randomUUID() + "@example.test";
        String toB = "b-" + UUID.randomUUID() + "@example.test";
        asTenant(tenantA, () -> transactions.execute(s -> outboxService.enqueue(tenantA, toA, "s", "b")));
        asTenant(tenantB, () -> transactions.execute(s -> outboxService.enqueue(tenantB, toB, "s", "b")));

        assertThat(allRecipients()).contains(toA, toB);
    }

    private UUID enqueue(String recipient) {
        return asTenant(
                tenantA,
                () ->
                        transactions.execute(
                                status -> outboxService.enqueue(tenantA, recipient, "Subject", "<p>Body</p>")));
    }

    /**
     * Repository access from the test thread needs system mode for the same reason the dispatcher
     * does — the transaction listener demands a tenant otherwise.
     */
    private EmailOutbox findRow(UUID id) {
        return TenantContext.runAsSystem(
                "test: read outbox row", () -> outbox.findById(id).orElseThrow());
    }

    private List<String> allRecipients() {
        return TenantContext.runAsSystem(
                "test: list outbox rows",
                () -> outbox.findAll().stream().map(EmailOutbox::toEmail).toList());
    }
}
