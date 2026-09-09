package com.caderly.caderlyhr.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.org.Department;
import com.caderly.caderlyhr.org.DepartmentRepository;
import com.caderly.caderlyhr.org.Division;
import com.caderly.caderlyhr.org.DivisionRepository;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code calendar}'s two entry points into {@code people} (sub-phase 1.8): resolving the iCal
 * token owner's employee id, and the team calendar grid's employee list filtered by
 * department/division. Also covers {@code listPeers}, Home's "My Peers" widget's query
 * (sub-phase 1.9 / ADR 0015).
 */
class PeopleFacadeImplTest extends TenantIsolationTestBase {

    @Autowired private PeopleFacade peopleFacade;
    @Autowired private EmployeeRepository employees;
    @Autowired private AppUserRepository appUsers;
    @Autowired private DivisionRepository divisionRepository;
    @Autowired private DepartmentRepository departmentRepository;

    @Test
    void findEmployeeIdByUserId_whenLinked_returnsTheEmployeeId() {
        // employee.user_id carries a real FK to app_user(id) (V202608021000), so this needs an
        // actual saved AppUser, not an arbitrary UUID.
        UUID userId = asTenant(tenantA, () -> appUsers.save(AppUser.active(uniqueEmail(), "hash"))).getId();
        Employee employee =
                asTenant(
                        tenantA,
                        () -> {
                            Employee e = Employee.create("Jane", "Doe", uniqueEmail());
                            e.linkUser(userId);
                            return employees.save(e);
                        });

        assertThat(asTenant(tenantA, () -> peopleFacade.findEmployeeIdByUserId(userId)))
                .contains(employee.requireId());
    }

    @Test
    void findEmployeeIdByUserId_whenNoEmployeeLinked_returnsEmpty() {
        assertThat(asTenant(tenantA, () -> peopleFacade.findEmployeeIdByUserId(UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    void listEmployeesForCalendar_withNoFilter_excludesTerminatedOnly() {
        Employee active = asTenant(tenantA, () -> saveEmployee("Active", "One", null));
        Employee terminated =
                asTenant(
                        tenantA,
                        () -> {
                            Employee e = saveEmployee("Gone", "Fromhere", null);
                            e.changeStatus(EmployeeStatus.TERMINATED);
                            return employees.save(e);
                        });

        List<PeopleFacade.EmployeeCalendarInfo> result =
                asTenant(tenantA, () -> peopleFacade.listEmployeesForCalendar(null, null));

        assertThat(result).extracting(PeopleFacade.EmployeeCalendarInfo::employeeId)
                .contains(active.requireId())
                .doesNotContain(terminated.requireId());
    }

    @Test
    void listEmployeesForCalendar_filteredByDepartment_returnsOnlyThatDepartment() {
        Division division = asTenant(tenantA, () -> divisionRepository.save(Division.create(uniqueName("Div"), null)));
        Department deptA =
                asTenant(tenantA, () -> departmentRepository.save(Department.create(uniqueName("DeptA"), null, division)));
        Department deptB =
                asTenant(tenantA, () -> departmentRepository.save(Department.create(uniqueName("DeptB"), null, division)));
        Employee inA = asTenant(tenantA, () -> saveEmployee("In", "DeptA", deptA));
        asTenant(tenantA, () -> saveEmployee("In", "DeptB", deptB));

        List<PeopleFacade.EmployeeCalendarInfo> result =
                asTenant(
                        tenantA,
                        () -> peopleFacade.listEmployeesForCalendar(deptA.requireId(), null));

        assertThat(result).extracting(PeopleFacade.EmployeeCalendarInfo::employeeId)
                .containsExactly(inA.requireId());
    }

    @Test
    void listEmployeesForCalendar_filteredByDivision_joinsThroughDepartment() {
        Division divisionA = asTenant(tenantA, () -> divisionRepository.save(Division.create(uniqueName("DivA"), null)));
        Division divisionB = asTenant(tenantA, () -> divisionRepository.save(Division.create(uniqueName("DivB"), null)));
        Department deptInA =
                asTenant(tenantA, () -> departmentRepository.save(Department.create(uniqueName("Dept"), null, divisionA)));
        Department deptInB =
                asTenant(tenantA, () -> departmentRepository.save(Department.create(uniqueName("Dept"), null, divisionB)));
        Employee inDivisionA = asTenant(tenantA, () -> saveEmployee("In", "DivisionA", deptInA));
        asTenant(tenantA, () -> saveEmployee("In", "DivisionB", deptInB));

        List<PeopleFacade.EmployeeCalendarInfo> result =
                asTenant(
                        tenantA,
                        () -> peopleFacade.listEmployeesForCalendar(null, divisionA.requireId()));

        assertThat(result).extracting(PeopleFacade.EmployeeCalendarInfo::employeeId)
                .containsExactly(inDivisionA.requireId());
    }

    @Test
    void listPeers_sameDepartment_excludesSelfAndTerminated() {
        Division division = asTenant(tenantA, () -> divisionRepository.save(Division.create(uniqueName("Div"), null)));
        Department dept =
                asTenant(tenantA, () -> departmentRepository.save(Department.create(uniqueName("Dept"), null, division)));
        Employee self = asTenant(tenantA, () -> saveEmployee("Self", "One", dept));
        Employee peer = asTenant(tenantA, () -> saveEmployee("Peer", "Two", dept));
        Employee terminatedPeer =
                asTenant(
                        tenantA,
                        () -> {
                            Employee e = saveEmployee("Gone", "Three", dept);
                            e.changeStatus(EmployeeStatus.TERMINATED);
                            return employees.save(e);
                        });

        List<PeopleFacade.EmployeePeerInfo> result = asTenant(tenantA, () -> peopleFacade.listPeers(self.requireId()));

        assertThat(result).extracting(PeopleFacade.EmployeePeerInfo::employeeId)
                .containsExactly(peer.requireId())
                .doesNotContain(self.requireId(), terminatedPeer.requireId());
    }

    @Test
    void listPeers_sameManagerDifferentDepartment_includesReports() {
        Employee manager = asTenant(tenantA, () -> saveEmployee("Boss", "One", null));
        Employee self = asTenant(tenantA, () -> saveEmployeeWithManager("Self", "Two", manager));
        Employee sibling = asTenant(tenantA, () -> saveEmployeeWithManager("Sibling", "Three", manager));

        List<PeopleFacade.EmployeePeerInfo> result = asTenant(tenantA, () -> peopleFacade.listPeers(self.requireId()));

        assertThat(result).extracting(PeopleFacade.EmployeePeerInfo::employeeId)
                .containsExactly(sibling.requireId());
    }

    @Test
    void listPeers_bothSameDepartmentAndSameManager_deduplicates() {
        Division division = asTenant(tenantA, () -> divisionRepository.save(Division.create(uniqueName("Div"), null)));
        Department dept =
                asTenant(tenantA, () -> departmentRepository.save(Department.create(uniqueName("Dept"), null, division)));
        Employee manager = asTenant(tenantA, () -> saveEmployee("Boss", "One", null));
        Employee self =
                asTenant(
                        tenantA,
                        () -> {
                            Employee e = saveEmployeeWithManager("Self", "Two", manager);
                            e.updateAdminFields(
                                    null, e.firstName(), e.lastName(), e.email(), null, null, null, null, null, null,
                                    null, null, dept, null, null, BigDecimal.valueOf(8.0), null, null);
                            return employees.save(e);
                        });
        Employee peer =
                asTenant(
                        tenantA,
                        () -> {
                            Employee e = saveEmployeeWithManager("Peer", "Three", manager);
                            e.updateAdminFields(
                                    null, e.firstName(), e.lastName(), e.email(), null, null, null, null, null, null,
                                    null, null, dept, null, null, BigDecimal.valueOf(8.0), null, null);
                            return employees.save(e);
                        });

        List<PeopleFacade.EmployeePeerInfo> result = asTenant(tenantA, () -> peopleFacade.listPeers(self.requireId()));

        assertThat(result).extracting(PeopleFacade.EmployeePeerInfo::employeeId)
                .containsExactly(peer.requireId());
    }

    @Test
    void listPeers_neitherDepartmentNorManager_returnsEmpty() {
        Employee self = asTenant(tenantA, () -> saveEmployee("Lone", "Wolf", null));
        asTenant(tenantA, () -> saveEmployee("Other", "Person", null));

        List<PeopleFacade.EmployeePeerInfo> result = asTenant(tenantA, () -> peopleFacade.listPeers(self.requireId()));

        assertThat(result).isEmpty();
    }

    private Employee saveEmployee(String firstName, String lastName, Department department) {
        Employee employee = Employee.create(firstName, lastName, uniqueEmail());
        employee.updateAdminFields(
                null,
                firstName,
                lastName,
                employee.email(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                department,
                null,
                null,
                BigDecimal.valueOf(8.0),
                null,
                null);
        return employees.save(employee);
    }

    private Employee saveEmployeeWithManager(String firstName, String lastName, Employee manager) {
        Employee employee = saveEmployee(firstName, lastName, null);
        employee.reassignManager(manager);
        return employees.save(employee);
    }

    private static String uniqueEmail() {
        return "person-" + UUID.randomUUID() + "@example.test";
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
