package com.caderly.caderlyhr.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.notifications.system.EmailDispatcher;
import com.caderly.caderlyhr.notifications.system.EmailStatus;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.support.PlaywrightE2ETestBase;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Sub-phase 1.10's DoD headline, end to end through a real browser: an SMTP outage burns an
 * email's retry budget, the Admin finds the FAILED row at {@code /admin/notifications}, clicks
 * Retry, and the row reaches SENT once the server is back.
 *
 * <p>The failure is injected at {@code JavaMailSender} rather than by pointing the app at a dead
 * port, so the outage can be ended mid-test without restarting anything.
 */
@Import(MutableClockConfiguration.class)
class NotificationsE2ETest extends PlaywrightE2ETestBase {

    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EmailDispatcher dispatcher;
    @Autowired private MutableClock clock;

    @MockitoBean private JavaMailSender mailSender;

    @BeforeEach
    void clearOutbox() {
        // Same reason as EmailOutboxTest.clearOutbox, with one extra bite: the dispatcher drains
        // at most 50 rows per poll, oldest first, so leftovers from earlier test classes in the
        // reused Testcontainers database can crowd this test's row out of every batch.
        TenantContext.runAsSystem(
                "e2e test: clear email outbox",
                () -> {
                    outbox.deleteAll();
                    return null;
                });
    }

    @BeforeEach
    void stubMailSender() {
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void adminSeesAFailedEmailAndRetriesItBackToSent() {
        String slug = "notif-e2e" + UUID.randomUUID().toString().substring(0, 8);
        baseUrl = "http://" + slug + ".localhost:" + port;
        String adminEmail = "admin-" + UUID.randomUUID() + "@example.test";
        seedTenantAndAdmin(slug, adminEmail);

        // Creating an employee sends them an invite; that invite is the mail the outage kills.
        String inviteeEmail = "invitee-" + UUID.randomUUID() + "@example.test";
        smtpIsDown();
        loginAs(adminEmail, "AdminPassphrase1");
        createEmployee(inviteeEmail);

        burnTheRetryBudget();
        assertThat(statusOf(inviteeEmail)).isEqualTo(EmailStatus.FAILED);

        page.navigate(baseUrl + "/admin/notifications");
        page.waitForSelector("#notifications-content");
        assertThat(page.content()).contains(inviteeEmail).contains("Failed");

        smtpIsBack();
        // Retry is icon-only now (aria-label, no visible text) — see admin/notifications.html.
        page.click("tr:has-text('" + inviteeEmail + "') button[aria-label='Retry this email']");
        page.waitForSelector("tr:has-text('" + inviteeEmail + "'):has-text('Pending')");

        dispatcher.dispatchPending();
        assertThat(statusOf(inviteeEmail)).isEqualTo(EmailStatus.SENT);

        page.navigate(baseUrl + "/admin/notifications");
        page.waitForSelector("tr:has-text('" + inviteeEmail + "'):has-text('Sent')");
    }

    /** Same flow as {@code EmployeeLifecycleE2ETest} — People > Add Employee sends the invite. */
    private void createEmployee(String email) {
        page.navigate(baseUrl + "/admin/employees");
        page.click("button:has-text('Add Employee')");
        page.waitForSelector("#create-firstName");
        page.fill("#create-firstName", "Jane");
        page.fill("#create-lastName", "Doe");
        page.fill("#create-email", email);
        page.click("#employeeOffcanvasBody button:has-text('Create')");
        page.waitForSelector(".toast-body:has-text('Employee created')");
    }

    private void smtpIsDown() {
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(MimeMessage.class));
    }

    private void smtpIsBack() {
        doNothing().when(mailSender).send(any(MimeMessage.class));
    }

    /**
     * Three failed attempts, advancing a {@link MutableClock} past the real 30s and 2m backoff
     * rather than sleeping — the same technique {@code EmailOutboxTest} uses, and the reason this
     * spec imports the mutable clock at all.
     */
    private void burnTheRetryBudget() {
        dispatcher.dispatchPending();
        clock.advance(Duration.ofSeconds(31));
        dispatcher.dispatchPending();
        clock.advance(Duration.ofMinutes(3));
        dispatcher.dispatchPending();
    }

    private EmailStatus statusOf(String email) {
        return TenantContext.runAsSystem(
                "e2e test: read outbox",
                () ->
                        outbox.findAll().stream()
                                .filter(row -> row.toEmail().equals(email))
                                .findFirst()
                                .orElseThrow()
                                .status());
    }

    private void seedTenantAndAdmin(String slug, String adminEmail) {
        UUID tenantId =
                TenantContext.runAsSystem(
                        "e2e test: seed tenant", () -> tenants.save(new Tenant(slug, "Notif E2E Co")).getId());
        TenantContext.set(tenantId);
        try {
            AppUser admin = AppUser.active(adminEmail, passwordEncoder.encode("AdminPassphrase1"));
            admin.grant(Role.ADMIN);
            appUsers.save(admin);
        } finally {
            TenantContext.clear();
        }
    }
}
