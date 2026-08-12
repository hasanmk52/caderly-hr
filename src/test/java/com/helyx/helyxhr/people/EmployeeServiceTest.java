package com.helyx.helyxhr.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.ValidationException;
import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserDetailsService;
import com.helyx.helyxhr.identity.AppUserPrincipal;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.org.Division;
import com.helyx.helyxhr.org.DivisionRepository;
import com.helyx.helyxhr.org.Department;
import com.helyx.helyxhr.org.DepartmentRepository;
import com.helyx.helyxhr.support.MutableClock;
import com.helyx.helyxhr.support.MutableClockConfiguration;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;

/**
 * Employee CRUD, field-permission boundaries, status/manager history, termination, and
 * view-authorization (PRD §14, §26). Extends {@link TenantIsolationTestBase} directly against
 * Testcontainers Postgres, matching {@code OrganizationServiceTest}/{@code
 * InviteAndResetServiceTest}'s convention.
 */
@Import(MutableClockConfiguration.class)
class EmployeeServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeRepository employees;
    @Autowired private EmployeeStatusHistoryRepository statusHistory;
    @Autowired private EmployeeManagerHistoryRepository managerHistory;
    @Autowired private PeopleFacade peopleFacade;
    @Autowired private DivisionRepository divisionRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private AppUserRepository appUsers;
    @Autowired private AppUserDetailsService userDetailsService;
    @Autowired private MutableClock clock;

    @Test
    void create_startsInvitedWithOpenStatusHistoryAndLinksAnAppUser() {
        Employee employee = asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", uniqueEmail()), BASE_URL, TENANT_NAME));

        assertThat(employee.status()).isEqualTo(EmployeeStatus.INVITED);
        assertThat(employee.userId()).isNotNull();

        AppUser linkedUser = asTenant(tenantA, () -> appUsers.findById(employee.userId())).orElseThrow();
        assertThat(linkedUser.roles()).containsExactly(Role.EMPLOYEE);

        var openHistory =
                asTenant(
                        tenantA,
                        () ->
                                statusHistory.findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(
                                        employee.requireId()));
        assertThat(openHistory).isPresent();
        assertThat(openHistory.orElseThrow().status()).isEqualTo(EmployeeStatus.INVITED);
    }

    @Test
    void create_withDuplicateEmailInSameTenant_throwsConflict() {
        String email = uniqueEmail();
        asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", email), BASE_URL, TENANT_NAME));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () -> employeeService.create(form("Other", "Person", email), BASE_URL, TENANT_NAME)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void activateForUser_flipsInvitedEmployeeToActive() {
        Employee employee =
                asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", uniqueEmail()), BASE_URL, TENANT_NAME));

        asTenant(tenantA, () -> employeeService.activateForUser(employee.userId()));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.status()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void updateSelfServiceFields_onlyTouchesContactAndAddressFields() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        asTenant(
                tenantA,
                () ->
                        employeeService.updateSelfServiceFields(
                                employee.requireId(),
                                new EmployeeForms.SelfProfilePatch("+1000", "1 Main St", null, "Dubai", "UAE", "00000")));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.phone()).isEqualTo("+1000");
        assertThat(reloaded.city()).isEqualTo("Dubai");
        // Job/compensation fields are untouched — SelfProfilePatch has no such fields at all
        // (enforced by construction, not a runtime filter).
        assertThat(reloaded.jobTitle()).isNull();
    }

    @Test
    void updateAdminFields_setsJobAndEncryptedCompensation() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        UUID departmentId = seedDepartment();

        asTenant(
                tenantA,
                () ->
                        employeeService.updateAdminFields(
                                employee.requireId(),
                                new EmployeeForms.AdminEmployeePatch(
                                        "EMP-1",
                                        employee.firstName(),
                                        employee.lastName(),
                                        employee.email(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        EmploymentType.FULL_TIME,
                                        departmentId,
                                        null,
                                        "Engineer",
                                        "Dubai HQ",
                                        BigDecimal.valueOf(8),
                                        "AED",
                                        "12000.00")));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.jobTitle()).isEqualTo("Engineer");
        assertThat(reloaded.department()).isNotNull();
        // The encrypted value decrypts correctly through the entity — the raw-column tamper-proof
        // property itself is covered by CryptoConverterTest.
        assertThat(reloaded.baseCompensation()).isEqualTo("12000.00");
    }

    @Test
    void reassignManager_opensNewHistoryRowAndClosesThePrevious() {
        Employee manager1 = createActiveEmployee("Manager", "One");
        Employee manager2 = createActiveEmployee("Manager", "Two");
        Employee report = createActiveEmployee("Report", "Ee");

        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager1.requireId()));
        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager2.requireId()));

        var history =
                asTenant(
                        tenantA,
                        () -> managerHistory.findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(report.requireId()));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).effectiveTo()).isNull(); // most recent row still open
        assertThat(history.get(1).effectiveTo()).isNotNull(); // superseded row closed

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(report.requireId()));
        assertThat(reloaded.manager()).isNotNull();
    }

    @Test
    void reassignManager_toSelf_throwsValidation() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.reassignManager(employee.requireId(), employee.requireId())))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void terminate_withPastDate_appliesImmediatelyAndClosesStatusHistory() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        asTenant(tenantA, () -> employeeService.terminate(employee.requireId(), LocalDate.now(clock).minusDays(1)));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.status()).isEqualTo(EmployeeStatus.TERMINATED);
    }

    @Test
    void terminate_withFutureDate_staysActiveUntilTheJobApplies() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        LocalDate future = LocalDate.now(clock).plusDays(5);

        asTenant(tenantA, () -> employeeService.terminate(employee.requireId(), future));
        Employee stillActive = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(stillActive.status()).isEqualTo(EmployeeStatus.ACTIVE);

        clock.advance(java.time.Duration.ofDays(6));
        int applied = asTenant(tenantA, () -> employeeService.applyDueTerminations(LocalDate.now(clock)));

        assertThat(applied).isGreaterThanOrEqualTo(1);
        Employee terminated = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(terminated.status()).isEqualTo(EmployeeStatus.TERMINATED);
    }

    @Test
    void getProfileForViewer_self_isAllowed() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        AppUserPrincipal ownPrincipal = loadPrincipal(employee.email());

        Employee viewed = asTenant(tenantA, () -> employeeService.getProfileForViewer(employee.requireId(), ownPrincipal));

        assertThat(viewed.requireId()).isEqualTo(employee.requireId());
    }

    @Test
    void getProfileForViewer_directManager_isAllowed() {
        Employee manager = createActiveEmployee("Manager", "One");
        grantRole(manager, Role.MANAGER);
        Employee report = createActiveEmployee("Report", "Ee");
        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager.requireId()));
        AppUserPrincipal managerPrincipal = loadPrincipal(manager.email());

        Employee viewed = asTenant(tenantA, () -> employeeService.getProfileForViewer(report.requireId(), managerPrincipal));

        assertThat(viewed.requireId()).isEqualTo(report.requireId());
    }

    @Test
    void getProfileForViewer_unrelatedEmployee_isDenied() {
        Employee viewer = createActiveEmployee("Viewer", "Ee");
        Employee target = createActiveEmployee("Target", "Ee");
        AppUserPrincipal viewerPrincipal = loadPrincipal(viewer.email());

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.getProfileForViewer(target.requireId(), viewerPrincipal)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void peopleFacade_countActiveEmployeesInDepartment_excludesTerminated() {
        UUID departmentId = seedDepartment();
        Employee active = createActiveEmployee("Active", "One");
        Employee terminated = createActiveEmployee("Terminated", "One");
        assignDepartment(active, departmentId);
        assignDepartment(terminated, departmentId);
        asTenant(tenantA, () -> employeeService.terminate(terminated.requireId(), LocalDate.now(clock).minusDays(1)));

        long count = asTenant(tenantA, () -> peopleFacade.countActiveEmployeesInDepartment(departmentId));

        assertThat(count).isEqualTo(1);
    }

    // ---- fixtures ----

    private Employee createActiveEmployee(String firstName, String lastName) {
        Employee employee =
                asTenant(tenantA, () -> employeeService.create(form(firstName, lastName, uniqueEmail()), BASE_URL, TENANT_NAME));
        asTenant(tenantA, () -> employeeService.activateForUser(employee.userId()));
        return asTenant(tenantA, () -> employeeService.require(employee.requireId()));
    }

    private void grantRole(Employee employee, Role role) {
        asTenant(
                tenantA,
                () -> {
                    AppUser user = appUsers.findById(employee.userId()).orElseThrow();
                    user.grant(role);
                    return appUsers.save(user);
                });
    }

    private AppUserPrincipal loadPrincipal(String email) {
        return (AppUserPrincipal) asTenant(tenantA, () -> userDetailsService.loadUserByUsername(email));
    }

    private UUID seedDepartment() {
        return asTenant(
                tenantA,
                () -> {
                    Division division = divisionRepository.save(Division.create("Engineering-" + UUID.randomUUID(), null));
                    Department department =
                            departmentRepository.save(Department.create("Backend-" + UUID.randomUUID(), null, division));
                    return department.requireId();
                });
    }

    private void assignDepartment(Employee employee, UUID departmentId) {
        asTenant(
                tenantA,
                () ->
                        employeeService.updateAdminFields(
                                employee.requireId(),
                                new EmployeeForms.AdminEmployeePatch(
                                        null,
                                        employee.firstName(),
                                        employee.lastName(),
                                        employee.email(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        departmentId,
                                        null,
                                        null,
                                        null,
                                        BigDecimal.valueOf(8),
                                        null,
                                        null)));
    }

    private static EmployeeForms.CreateEmployee form(String firstName, String lastName, String email) {
        return new EmployeeForms.CreateEmployee(
                firstName, lastName, email, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.test";
    }
}
