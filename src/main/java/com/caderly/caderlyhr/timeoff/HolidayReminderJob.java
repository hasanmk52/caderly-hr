package com.caderly.caderlyhr.timeoff;

import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the day-before public-holiday reminder (PRD §17.2). Same shape as {@code
 * people.EmployeeTerminationJob}: externalised cron, a disable flag tests flip, and a public
 * method tests call at a known moment instead of racing the scheduler.
 *
 * <p>Like that job — and unlike {@code EmailDispatcher} — it does <em>not</em> use {@code
 * runAsSystem}: {@code public_holiday} and {@code employee} are RLS-protected, and system mode is
 * not an RLS bypass (ADR 0003). It fans out over active tenants and sets a real context for each.
 *
 * <p>Runs in the evening rather than at midnight so "tomorrow" still reads as tomorrow to someone
 * who opens the mail before bed.
 */
@Component
public class HolidayReminderJob {

    private final HolidayReminderService reminders;
    private final TenantFacade tenants;
    private final Clock clock;
    private final boolean enabled;

    HolidayReminderJob(
            HolidayReminderService reminders,
            TenantFacade tenants,
            Clock clock,
            @Value("${caderly.timeoff.holiday-reminder-job.enabled:true}") boolean enabled) {
        this.reminders = reminders;
        this.tenants = tenants;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${caderly.timeoff.holiday-reminder-job.cron:0 0 17 * * *}")
    void scheduledRun() {
        if (!enabled) {
            return;
        }
        remindAboutTomorrow();
    }

    public int remindAboutTomorrow() {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        int total = 0;
        for (UUID tenantId : tenants.listActiveTenantIds()) {
            TenantContext.set(tenantId);
            try {
                total += reminders.remindAbout(tomorrow);
            } finally {
                TenantContext.clear();
            }
        }
        return total;
    }
}
