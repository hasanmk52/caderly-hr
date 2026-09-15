package com.caderly.caderlyhr.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.notifications.EmailEvent;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import com.caderly.caderlyhr.tenant.TenantFacade.NotificationSettings;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * PRD §17.2's three employee-date reminders: birthday, work anniversary, and expiring government
 * ID (the "Document expiring" row — FR-3.7 is where the expiry date lives and already promises
 * "Expiry monitored for reminders").
 */
@Import(MutableClockConfiguration.class)
class ReminderServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Tenant A";

    @Autowired private EmployeeService employees;
    @Autowired private ReminderService reminders;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private TenantFacade tenants;
    @Autowired private MutableClock clock;

    private LocalDate today;

    @BeforeEach
    void clearOutboxAndEnableEveryCategory() {
        today = LocalDate.now(clock);
        TenantContext.runAsSystem(
                "test: clear email outbox",
                () -> {
                    outbox.deleteAll();
                    return null;
                });
        // Birthday and anniversary ship off (PRD §17.2 "opt-in per tenant"); these tests are
        // about what fires once a tenant has opted in. NotificationCategoryToggleTest owns the
        // switched-off behaviour.
        asTenant(
                tenantA,
                () -> tenants.updateNotificationSettings(new NotificationSettings(true, true, true, true)));
    }

    @Test
    void sendCelebrationReminders_onTheBirthday_tellsTheTeamButNotTheCelebrant() {
        Employee manager = create("Manager", "One", null, null, null);
        Employee celebrant = create("Priya", "Nair", today.minusYears(30), null, manager.requireId());
        Employee peer = create("Sam", "Peer", null, null, manager.requireId());

        asTenant(tenantA, () -> reminders.sendCelebrationReminders(today));

        assertThat(recipientsOf(EmailEvent.BIRTHDAY))
                .containsExactly(peer.email())
                .doesNotContain(celebrant.email());
    }

    @Test
    void sendCelebrationReminders_onADifferentDay_sendsNothing() {
        Employee manager = create("Manager", "Two", null, null, null);
        create("Priya", "Nair", today.minusYears(30), null, manager.requireId());
        create("Sam", "Peer", null, null, manager.requireId());

        asTenant(tenantA, () -> reminders.sendCelebrationReminders(today.plusDays(1)));

        assertThat(recipientsOf(EmailEvent.BIRTHDAY)).isEmpty();
    }

    @Test
    void sendCelebrationReminders_onTheHireDateAnniversary_announcesTheYearCount() {
        Employee manager = create("Manager", "Three", null, null, null);
        create("Anniv", "Person", null, today.minusYears(3), manager.requireId());
        Employee peer = create("Sam", "Peer", null, null, manager.requireId());

        asTenant(tenantA, () -> reminders.sendCelebrationReminders(today));

        assertThat(recipientsOf(EmailEvent.WORK_ANNIVERSARY)).containsExactly(peer.email());
        assertThat(subjectsOf(EmailEvent.WORK_ANNIVERSARY).getFirst()).contains("3 years ago today");
    }

    @Test
    void sendCelebrationReminders_onTheHireDateItself_isNotAnAnniversary() {
        // Year zero is the day they joined. Congratulating someone on their nought-th anniversary
        // on their first morning would be a bug, not a welcome.
        Employee manager = create("Manager", "Four", null, null, null);
        create("New", "Starter", null, today, manager.requireId());
        create("Sam", "Peer", null, null, manager.requireId());

        asTenant(tenantA, () -> reminders.sendCelebrationReminders(today));

        assertThat(recipientsOf(EmailEvent.WORK_ANNIVERSARY)).isEmpty();
    }

    @Test
    void sendCelebrationReminders_whenTheCelebrantHasNoTeam_sendsNothing() {
        create("Lone", "Wolf", today.minusYears(40), null, null);

        asTenant(tenantA, () -> reminders.sendCelebrationReminders(today));

        assertThat(recipientsOf(EmailEvent.BIRTHDAY)).isEmpty();
    }

    @Test
    void sendCelebrationReminders_runTwiceOnTheSameDay_doesNotDuplicate() {
        // A restart across the cron minute is a restart, not a second birthday.
        Employee manager = create("Manager", "Five", null, null, null);
        create("Priya", "Nair", today.minusYears(30), null, manager.requireId());
        create("Sam", "Peer", null, null, manager.requireId());

        asTenant(tenantA, () -> reminders.sendCelebrationReminders(today));
        asTenant(tenantA, () -> reminders.sendCelebrationReminders(today));

        assertThat(recipientsOf(EmailEvent.BIRTHDAY)).hasSize(1);
    }

    @Test
    void sendDocumentExpiryReminders_atEachWindow_notifiesTheEmployee() {
        Employee owner = create("Doc", "Owner", null, null, null);
        addPassport(owner, today.plusDays(30));

        asTenant(tenantA, () -> reminders.sendDocumentExpiryReminders(today));

        assertThat(recipientsOf(EmailEvent.DOCUMENT_EXPIRY)).contains(owner.email());
        assertThat(subjectsOf(EmailEvent.DOCUMENT_EXPIRY).getFirst()).isEqualTo("Passport expires in 30 days");
    }

    @Test
    void sendDocumentExpiryReminders_betweenWindows_sendsNothing() {
        // Exact dates, not a range: an ID is flagged three times, not every day for a month.
        Employee owner = create("Doc", "Owner", null, null, null);
        addPassport(owner, today.plusDays(29));

        asTenant(tenantA, () -> reminders.sendDocumentExpiryReminders(today));

        assertThat(recipientsOf(EmailEvent.DOCUMENT_EXPIRY)).isEmpty();
    }

    @Test
    void sendDocumentExpiryReminders_neverPutsTheIdNumberInTheBody() {
        // government_id.id_number is encrypted at rest (ADR 0008); an inbox is not somewhere to
        // decrypt it into.
        Employee owner = create("Doc", "Owner", null, null, null);
        addPassport(owner, today.plusDays(7));

        asTenant(tenantA, () -> reminders.sendDocumentExpiryReminders(today));

        assertThat(bodiesOf(EmailEvent.DOCUMENT_EXPIRY)).isNotEmpty();
        assertThat(bodiesOf(EmailEvent.DOCUMENT_EXPIRY)).noneMatch(body -> body.contains("X1234567"));
    }

    @Test
    void sendDocumentExpiryReminders_forATerminatedEmployee_sendsNothing() {
        Employee owner = create("Gone", "Away", null, null, null);
        addPassport(owner, today.plusDays(14));
        asTenant(tenantA, () -> employees.terminate(owner.requireId(), today));
        asTenant(tenantA, () -> employees.applyDueTerminations(today));

        asTenant(tenantA, () -> reminders.sendDocumentExpiryReminders(today));

        assertThat(recipientsOf(EmailEvent.DOCUMENT_EXPIRY)).isEmpty();
    }

    private Employee create(
            String firstName,
            String lastName,
            java.time.LocalDate birthDate,
            java.time.LocalDate hireDate,
            UUID managerId) {
        return asTenant(
                tenantA,
                () ->
                        employees.create(
                                new EmployeeForms.CreateEmployee(
                                        firstName,
                                        lastName,
                                        UUID.randomUUID() + "@example.test",
                                        null,
                                        null,
                                        birthDate,
                                        null,
                                        null,
                                        null,
                                        null,
                                        hireDate,
                                        null,
                                        null,
                                        managerId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null),
                                BASE_URL,
                                TENANT_NAME));
    }

    private void addPassport(Employee owner, LocalDate expiry) {
        asTenant(
                tenantA,
                () ->
                        employees.addGovernmentId(
                                owner.requireId(), GovernmentIdType.PASSPORT, "X1234567", "India", null, expiry));
    }

    private List<String> recipientsOf(EmailEvent event) {
        return rowsOf(event).stream().map(EmailOutbox::toEmail).toList();
    }

    private List<String> subjectsOf(EmailEvent event) {
        return rowsOf(event).stream().map(EmailOutbox::subject).toList();
    }

    private List<String> bodiesOf(EmailEvent event) {
        return rowsOf(event).stream().map(EmailOutbox::bodyHtml).toList();
    }

    private List<EmailOutbox> rowsOf(EmailEvent event) {
        return TenantContext.runAsSystem(
                "test: read outbox",
                () ->
                        outbox.findAll().stream()
                                .filter(row -> event.name().equals(row.eventType()))
                                .toList());
    }
}
