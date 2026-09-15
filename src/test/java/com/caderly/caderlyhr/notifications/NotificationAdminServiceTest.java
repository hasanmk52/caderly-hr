package com.caderly.caderlyhr.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.common.ValidationException;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code email_outbox} is the one table in the app with no RLS policy and no {@code @TenantId}
 * (ADR 0005 decision B), so the isolation this file asserts exists nowhere else: delete the
 * {@code tenantId} predicate in {@link NotificationAdminService} and nothing underneath it
 * objects. The first and the cross-tenant retry test should both fail if that predicate goes.
 *
 * <p>FAILED rows are produced by driving the real dispatcher against a sender that always throws,
 * not by writing {@code status} — the retry UI has to work against rows the system actually
 * produced, including their attempt count.
 */
@Import(MutableClockConfiguration.class)
class NotificationAdminServiceTest extends TenantIsolationTestBase {

    private static final Map<String, Object> MODEL =
            Map.of("acceptUrl", "https://acme.localhost/accept-invite?token=t");

    @Autowired private NotificationAdminService admin;
    @Autowired private EmailOutboxService outboxService;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private EmailDispatcher dispatcher;
    @Autowired private TransactionTemplate transactions;
    @Autowired private MutableClock clock;

    @MockitoBean private JavaMailSender mailSender;

    @BeforeEach
    void stubMailSender() {
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @BeforeEach
    void clearOutbox() {
        // Same reason as EmailOutboxTest.clearOutbox: system-scoped table, reused container.
        TenantContext.runAsSystem(
                "test: clear email outbox",
                () -> {
                    outbox.deleteAll();
                    return null;
                });
    }

    @Test
    void list_showsOnlyTheCurrentTenantsMail() {
        String toA = enqueueFor(tenantA);
        String toB = enqueueFor(tenantB);

        assertThat(recipientsVisibleTo(tenantA)).containsExactly(toA);
        assertThat(recipientsVisibleTo(tenantB)).containsExactly(toB);
    }

    @Test
    void list_whenFilteredByStatus_returnsOnlyThatStatus() {
        // Failed row first: driving it to FAILED runs the dispatcher, which would sweep up any
        // row already PENDING alongside it.
        UUID failedId = failedRowFor(tenantA);
        enqueueFor(tenantA);

        assertThat(rowIds(EmailStatus.FAILED)).containsExactly(failedId);
        assertThat(rowIds(EmailStatus.PENDING)).doesNotContain(failedId).hasSize(1);
    }

    @Test
    void list_whenFilteredToADayWithNoMail_returnsNothing() {
        enqueueFor(tenantA);
        LocalDate yesterday = today().minusDays(1);

        assertThat(asTenant(tenantA, () -> admin.list(null, yesterday, yesterday, 0)).getContent())
                .isEmpty();
    }

    @Test
    void list_whenFilteredToToday_includesMailQueuedToday() {
        String recipient = enqueueFor(tenantA);
        LocalDate today = today();

        assertThat(asTenant(tenantA, () -> admin.list(null, today, today, 0)).getContent())
                .extracting(NotificationAdminService.OutboxRow::toEmail)
                .containsExactly(recipient);
    }

    @Test
    void requeue_onFailedRow_returnsItToPendingWithAFreshAttemptBudget() {
        UUID failedId = failedRowFor(tenantA);
        clock.advance(Duration.ofMinutes(5));

        asTenant(tenantA, () -> admin.requeue(failedId));

        EmailOutbox row = findRow(failedId);
        assertThat(row.status()).isEqualTo(EmailStatus.PENDING);
        assertThat(row.attempts()).isZero();
        assertThat(row.nextAttemptAt()).isEqualTo(clock.instant());
        // Kept, not cleared: until the retry succeeds it is still the best explanation.
        assertThat(row.lastError()).isNotNull();
    }

    @Test
    void requeue_onAnotherTenantsRow_reportsNotFoundRatherThanForbidden() {
        UUID tenantBRow = failedRowFor(tenantB);

        assertThatThrownBy(() -> asTenant(tenantA, () -> admin.requeue(tenantBRow)))
                .isInstanceOf(NotFoundException.class);
        // The row is untouched — a refused retry must not half-apply.
        assertThat(findRow(tenantBRow).status()).isEqualTo(EmailStatus.FAILED);
    }

    @Test
    void requeue_onARowThatDidNotFail_isRejected() {
        UUID pendingId = enqueueIdFor(tenantA, "pending");

        assertThatThrownBy(() -> asTenant(tenantA, () -> admin.requeue(pendingId)))
                .isInstanceOf(ValidationException.class);
    }

    /**
     * Wall-clock, not the MutableClock: {@code created_at} comes from Hibernate's
     * {@code @CreationTimestamp} on {@code BaseEntity}, which does not consult the injected Clock.
     * The date filter reads that column, so the test has to ask the same clock it does. The
     * fixture tenants default to UTC.
     */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private List<UUID> rowIds(EmailStatus status) {
        return asTenant(tenantA, () -> admin.list(status, null, null, 0)).getContent().stream()
                .map(NotificationAdminService.OutboxRow::id)
                .toList();
    }

    private List<String> recipientsVisibleTo(UUID tenantId) {
        return asTenant(tenantId, () -> admin.list(null, null, null, 0)).getContent().stream()
                .map(NotificationAdminService.OutboxRow::toEmail)
                .toList();
    }

    private String enqueueFor(UUID tenantId) {
        String recipient = "to-" + UUID.randomUUID() + "@example.test";
        asTenant(
                tenantId,
                () ->
                        transactions.execute(
                                status -> outboxService.enqueue(EmailEvent.INVITE, recipient, MODEL, "Acme")));
        return recipient;
    }

    private UUID enqueueIdFor(UUID tenantId, String prefix) {
        return asTenant(
                        tenantId,
                        () ->
                                transactions.execute(
                                        status ->
                                                outboxService.enqueue(
                                                        EmailEvent.INVITE,
                                                        prefix + "-" + UUID.randomUUID() + "@example.test",
                                                        MODEL,
                                                        "Acme")))
                .orElseThrow();
    }

    /** Burns the row's whole retry budget against a sender that always throws. */
    private UUID failedRowFor(UUID tenantId) {
        UUID rowId = enqueueIdFor(tenantId, "doomed");
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(MimeMessage.class));
        dispatcher.dispatchPending();
        clock.advance(Duration.ofSeconds(31));
        dispatcher.dispatchPending();
        clock.advance(Duration.ofMinutes(3));
        dispatcher.dispatchPending();

        assertThat(findRow(rowId).status()).isEqualTo(EmailStatus.FAILED);
        return rowId;
    }

    private EmailOutbox findRow(UUID id) {
        return TenantContext.runAsSystem(
                "test: read outbox row", () -> outbox.findById(id).orElseThrow());
    }
}
