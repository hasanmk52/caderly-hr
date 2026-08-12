package com.helyx.helyxhr.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.helyx.helyxhr.support.MutableClock;
import com.helyx.helyxhr.support.MutableClockConfiguration;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Proves {@link EmployeeTerminationJob} fans out over real tenant contexts (not {@code
 * runAsSystem}, which reads zero rows for RLS-protected tables — see the job's own Javadoc and
 * ADR 0003) and actually applies a due termination it finds along the way.
 */
@Import(MutableClockConfiguration.class)
class EmployeeTerminationJobTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeTerminationJob job;
    @Autowired private MutableClock clock;

    @Test
    void applyDueTerminations_findsAndAppliesAFutureTerminationOnceItsDateArrives() {
        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        new EmployeeForms.CreateEmployee(
                                                "Jane", "Doe", java.util.UUID.randomUUID() + "@example.test", null,
                                                null, null, null, null, null, null, null, null, null, null, null,
                                                null, null, null, null),
                                        BASE_URL,
                                        TENANT_NAME));
        asTenant(tenantA, () -> employeeService.terminate(employee.requireId(), LocalDate.now(clock).plusDays(2)));

        clock.advance(Duration.ofDays(3));
        int applied = job.applyDueTerminations();

        assertThat(applied).isGreaterThanOrEqualTo(1);
        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.status()).isEqualTo(EmployeeStatus.TERMINATED);
    }
}
