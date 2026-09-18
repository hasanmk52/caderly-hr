package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One morning sweep for the three date-driven reminders in {@link ReminderService} (PRD §17.2).
 * Same shape as {@link EmployeeTerminationJob}, including the reason it sets a real tenant context
 * per iteration rather than using {@code runAsSystem}: {@code employee} and {@code government_id}
 * are RLS-protected, and system mode is not an RLS bypass (ADR 0003).
 *
 * <p>Celebrations and expiry warnings run in separate transactions on purpose. A poison row in one
 * — an employee whose peer list blows up, say — must not roll back the other's queued mail.
 */
@Component
public class DailyReminderJob {

    private final ReminderService reminders;
    private final TenantFacade tenants;
    private final Clock clock;
    private final boolean enabled;

    DailyReminderJob(
            ReminderService reminders,
            TenantFacade tenants,
            Clock clock,
            @Value("${caderly.people.daily-reminder-job.enabled:true}") boolean enabled) {
        this.reminders = reminders;
        this.tenants = tenants;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${caderly.people.daily-reminder-job.cron:0 0 8 * * *}")
    void scheduledRun() {
        if (!enabled) {
            return;
        }
        sendDueReminders();
    }

    public int sendDueReminders() {
        LocalDate today = LocalDate.now(clock);
        int total = 0;
        for (UUID tenantId : tenants.listActiveTenantIds()) {
            TenantContext.set(tenantId);
            try {
                total += reminders.sendCelebrationReminders(today);
                total += reminders.sendDocumentExpiryReminders(today);
            } finally {
                TenantContext.clear();
            }
        }
        return total;
    }
}
