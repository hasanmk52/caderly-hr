package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Applies terminations whose {@code termination_date} has arrived (PRD §14.4). Mirrors {@code
 * notifications.system.EmailDispatcher}'s shape (externalized cron, a disable flag tests can
 * flip, a public method tests call directly at a known moment) with one addition: {@code
 * employee} is RLS-protected per tenant, unlike {@code email_outbox}, so {@code
 * TenantContext.runAsSystem} alone would read zero rows (ADR 0003 — system mode is not an RLS
 * bypass). This job instead fans out over {@link TenantFacade#listActiveTenantIds()} and sets a
 * real, resolved tenant context for each one in turn.
 */
@Component
public class EmployeeTerminationJob {

    private static final Logger log = LoggerFactory.getLogger(EmployeeTerminationJob.class);

    private final EmployeeService employees;
    private final TenantFacade tenants;
    private final Clock clock;
    private final boolean enabled;

    EmployeeTerminationJob(
            EmployeeService employees,
            TenantFacade tenants,
            Clock clock,
            @Value("${caderly.people.termination-job.enabled:true}") boolean enabled) {
        this.employees = employees;
        this.tenants = tenants;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${caderly.people.termination-job.cron:0 0 0 * * *}")
    void scheduledRun() {
        if (!enabled) {
            return;
        }
        applyDueTerminations();
    }

    public int applyDueTerminations() {
        LocalDate today = LocalDate.now(clock);
        int total = 0;
        for (UUID tenantId : tenants.listActiveTenantIds()) {
            TenantContext.set(tenantId);
            try {
                int applied = employees.applyDueTerminations(today);
                total += applied;
                if (applied > 0) {
                    log.info("Applied {} due termination(s) for tenant {}", applied, tenantId);
                }
            } finally {
                TenantContext.clear();
            }
        }
        return total;
    }
}
