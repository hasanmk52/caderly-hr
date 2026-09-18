package com.caderly.caderlyhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.notifications.EmailEvent;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.people.EmployeeForms;
import com.caderly.caderlyhr.people.EmployeeService;
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

/** PRD §17.2: "Public holiday tomorrow → all active users". */
@Import(MutableClockConfiguration.class)
class HolidayReminderServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Tenant A";

    @Autowired private HolidayReminderService reminders;
    @Autowired private PublicHolidayService holidays;
    @Autowired private EmployeeService employees;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private TenantFacade tenants;
    @Autowired private MutableClock clock;

    private LocalDate tomorrow;

    @BeforeEach
    void clearOutbox() {
        tomorrow = LocalDate.now(clock).plusDays(1);
        TenantContext.runAsSystem(
                "test: clear email outbox",
                () -> {
                    outbox.deleteAll();
                    return null;
                });
    }

    @Test
    void remindAbout_aDayWithAHoliday_tellsEveryActiveEmployee() {
        String first = createEmployee();
        String second = createEmployee();
        asTenant(tenantA, () -> holidays.create(tomorrow, "Eid al-Adha"));

        int queued = asTenant(tenantA, () -> reminders.remindAbout(tomorrow));

        assertThat(queued).isEqualTo(2);
        assertThat(recipients()).containsExactlyInAnyOrder(first, second);
        assertThat(subjects().getFirst()).isEqualTo("Reminder: Eid al-Adha tomorrow");
    }

    @Test
    void remindAbout_aDayWithNoHoliday_sendsNothing() {
        createEmployee();

        int queued = asTenant(tenantA, () -> reminders.remindAbout(tomorrow));

        assertThat(queued).isZero();
        assertThat(recipients()).isEmpty();
    }

    @Test
    void remindAbout_whenTheCategoryIsOff_sendsNothing() {
        createEmployee();
        asTenant(tenantA, () -> holidays.create(tomorrow, "Eid al-Adha"));
        asTenant(
                tenantA,
                () -> tenants.updateNotificationSettings(new NotificationSettings(false, true, false, false)));

        int queued = asTenant(tenantA, () -> reminders.remindAbout(tomorrow));

        assertThat(queued).isZero();
        assertThat(recipients()).isEmpty();
    }

    @Test
    void remindAbout_runTwiceOnTheSameDay_doesNotDuplicate() {
        // A restart across the cron minute must not mail the whole company twice.
        String recipient = createEmployee();
        asTenant(tenantA, () -> holidays.create(tomorrow, "Eid al-Adha"));

        asTenant(tenantA, () -> reminders.remindAbout(tomorrow));
        int second = asTenant(tenantA, () -> reminders.remindAbout(tomorrow));

        assertThat(second).isZero();
        assertThat(recipients()).containsExactly(recipient);
    }

    @Test
    void remindAbout_doesNotReachAnotherTenantsEmployees() {
        String inA = createEmployee();
        asTenant(tenantA, () -> holidays.create(tomorrow, "Eid al-Adha"));
        String inB =
                asTenant(
                        tenantB,
                        () ->
                                employees
                                        .create(newEmployee(), BASE_URL, "Tenant B")
                                        .email());

        asTenant(tenantA, () -> reminders.remindAbout(tomorrow));

        assertThat(recipients()).containsExactly(inA).doesNotContain(inB);
    }

    private String createEmployee() {
        return asTenant(tenantA, () -> employees.create(newEmployee(), BASE_URL, TENANT_NAME).email());
    }

    private static EmployeeForms.CreateEmployee newEmployee() {
        return new EmployeeForms.CreateEmployee(
                "Test", "Person", UUID.randomUUID() + "@example.test", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private List<String> recipients() {
        return holidayRows().stream().map(EmailOutbox::toEmail).toList();
    }

    private List<String> subjects() {
        return holidayRows().stream().map(EmailOutbox::subject).toList();
    }

    private List<EmailOutbox> holidayRows() {
        return TenantContext.runAsSystem(
                "test: read outbox",
                () ->
                        outbox.findAll().stream()
                                .filter(row -> EmailEvent.HOLIDAY_REMINDER.name().equals(row.eventType()))
                                .toList());
    }
}
