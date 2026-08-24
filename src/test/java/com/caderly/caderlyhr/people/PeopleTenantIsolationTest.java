package com.caderly.caderlyhr.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CLAUDE.md §5 rule 8: every new tenant-scoped entity gets a test proving cross-tenant reads come
 * back empty. Covers {@link Employee} plus one sub-entity ({@link EmergencyContact}) — the other
 * six (status/manager history, education, government id, bank detail, benefit) share the exact
 * same {@code TenantAwareEntity} + RLS-template mechanism and are exercised indirectly through
 * {@code EmployeeServiceTest}; this test's job is proving the mechanism itself, not repeating it
 * eight times (mirrors {@code OrgTenantIsolationTest}'s scope for Division/Department).
 */
class PeopleTenantIsolationTest extends TenantIsolationTestBase {

    @Autowired private EmployeeRepository employees;
    @Autowired private EmergencyContactRepository emergencyContacts;
    @Autowired private PostgreSQLContainer postgres;

    private String emailA;
    private String emailB;

    @BeforeEach
    void seedEmployees() {
        emailA = "a-" + UUID.randomUUID() + "@example.test";
        emailB = "b-" + UUID.randomUUID() + "@example.test";
        asTenant(tenantA, () -> employees.save(Employee.create("Alice", "A", emailA)));
        asTenant(tenantB, () -> employees.save(Employee.create("Bob", "B", emailB)));
    }

    @Test
    void findAll_whenTenantAActive_returnsOnlyTenantAEmployees() {
        List<Employee> visible = asTenant(tenantA, () -> employees.findAll());

        assertThat(visible).isNotEmpty();
        assertThat(visible).allSatisfy(e -> assertThat(e.getTenantId()).isEqualTo(tenantA));
        assertThat(visible).extracting(Employee::email).contains(emailA).doesNotContain(emailB);
    }

    @Test
    void save_whenTenantAActive_assignsTenantIdAutomatically() {
        Employee saved =
                asTenant(tenantA, () -> employees.save(Employee.create("Auto", "Assign", uniqueEmail())));

        assertThat(saved.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void findById_whenTenantBActiveOnTenantAEmployee_returnsEmpty() {
        UUID employeeAId =
                asTenant(tenantA, () -> employees.findAll()).stream()
                        .filter(e -> e.email().equals(emailA))
                        .findFirst()
                        .orElseThrow()
                        .getId();

        assertThat(asTenant(tenantB, () -> employees.findById(employeeAId))).isEmpty();
    }

    @Test
    void existsByEmailIgnoreCase_whenEmployeeBelongsToOtherTenant_returnsFalse() {
        assertThat(asTenant(tenantB, () -> employees.existsByEmailIgnoreCase(emailA))).isFalse();
        assertThat(asTenant(tenantA, () -> employees.existsByEmailIgnoreCase(emailA))).isTrue();
    }

    @Test
    void emergencyContacts_seededInTenantA_areInvisibleToTenantB() {
        UUID employeeAId =
                asTenant(tenantA, () -> employees.findAll()).stream()
                        .filter(e -> e.email().equals(emailA))
                        .findFirst()
                        .orElseThrow()
                        .getId();
        asTenant(
                tenantA,
                () -> {
                    Employee employee = employees.findById(employeeAId).orElseThrow();
                    return emergencyContacts.save(
                            EmergencyContact.create(employee, "Next of Kin", "Sibling", "+1000", null));
                });

        assertThat(asTenant(tenantA, () -> emergencyContacts.findAllByEmployeeIdOrderByNameAsc(employeeAId)))
                .hasSize(1);
        assertThat(asTenant(tenantB, () -> emergencyContacts.findAllByEmployeeIdOrderByNameAsc(employeeAId)))
                .isEmpty();
    }

    @Test
    void rawJdbc_withTenantASetting_rlsReturnsOnlyTenantAEmployees() throws Exception {
        assertThat(selectEmployeeEmailsAsRestrictedRole(tenantA)).contains(emailA).doesNotContain(emailB);
    }

    @Test
    void rawJdbc_withoutTenantSetting_rlsReturnsNoEmployees() throws Exception {
        assertThat(selectEmployeeEmailsAsRestrictedRole(null)).isEmpty();
    }

    private List<String> selectEmployeeEmailsAsRestrictedRole(UUID tenantId) throws Exception {
        List<String> emails = new ArrayList<>();
        try (Connection connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), "rls_probe", "rls_probe")) {
            connection.setAutoCommit(false);
            try {
                if (tenantId != null) {
                    try (PreparedStatement ps =
                            connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                        ps.setString(1, tenantId.toString());
                        ps.execute();
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement("SELECT email FROM caderly_hr.employee");
                        ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        emails.add(rs.getString(1));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        return emails;
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.test";
    }
}
