package com.caderly.caderlyhr.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The self-serve seat cap (ADR 0022 decision F): a workspace with a {@code seat_limit} can't hold
 * more non-terminated employees than that, a terminated employee frees their seat, and a NULL limit
 * (every Super Admin-created tenant) means no cap.
 */
class EmployeeSeatLimitTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";

    @Autowired private EmployeeService employeeService;

    @Test
    void create_whenTheWorkspaceIsFull_throwsSeatLimitReached() {
        setSeatLimit(tenantA, 2);
        createIn(tenantA);
        createIn(tenantA);

        assertThatThrownBy(() -> createIn(tenantA))
                .isInstanceOf(ValidationException.class)
                .extracting("errorCode")
                .isEqualTo("EMPLOYEE_SEAT_LIMIT_REACHED");
    }

    @Test
    void create_whenAnotherTenantIsFull_isUnaffected() {
        setSeatLimit(tenantA, 1);
        createIn(tenantA);

        createIn(tenantB);
        createIn(tenantB);

        assertThat(asTenant(tenantB, () -> employeeService.list(null, null).size())).isEqualTo(2);
    }

    @Test
    void create_afterAnEmployeeIsTerminated_reusesTheirSeat() {
        setSeatLimit(tenantA, 1);
        Employee first = createIn(tenantA);

        asTenant(tenantA, () -> employeeService.terminate(first.requireId(), LocalDate.now().minusDays(1)));

        assertThat(createIn(tenantA).requireId()).isNotEqualTo(first.requireId());
    }

    @Test
    void create_whenNoLimitIsSet_isNotCapped() {
        setSeatLimit(tenantA, null);
        for (int i = 0; i < 3; i++) {
            createIn(tenantA);
        }
        assertThat(asTenant(tenantA, () -> employeeService.list(null, null).size())).isEqualTo(3);
    }

    private Employee createIn(UUID tenantId) {
        String email = UUID.randomUUID() + "@example.test";
        return asTenant(
                tenantId,
                () ->
                        employeeService.create(
                                new EmployeeForms.CreateEmployee(
                                        "Seat", "Holder", email, null, null, null, null, null, null, null, null, null,
                                        null, null, null, null, null, null, null),
                                BASE_URL,
                                "Acme"));
    }

    private void setSeatLimit(UUID tenantId, @Nullable Integer limit) {
        TenantContext.runAsSystem(
                "test: set seat limit",
                () -> {
                    var tenant = tenantRepository.findById(tenantId).orElseThrow();
                    tenant.changeSeatLimit(limit);
                    return tenantRepository.save(tenant);
                });
    }
}
