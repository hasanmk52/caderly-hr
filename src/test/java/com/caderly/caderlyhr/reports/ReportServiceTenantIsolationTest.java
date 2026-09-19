package com.caderly.caderlyhr.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.org.Department;
import com.caderly.caderlyhr.org.DepartmentRepository;
import com.caderly.caderlyhr.org.Division;
import com.caderly.caderlyhr.org.DivisionRepository;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeRepository;
import com.caderly.caderlyhr.people.EmployeeStatus;
import com.caderly.caderlyhr.people.EmployeeStatusHistory;
import com.caderly.caderlyhr.people.EmployeeStatusHistoryRepository;
import com.caderly.caderlyhr.people.EmploymentType;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import com.caderly.caderlyhr.timeoff.LeaveBalance;
import com.caderly.caderlyhr.timeoff.LeaveBalanceRepository;
import com.caderly.caderlyhr.timeoff.LeaveRequest;
import com.caderly.caderlyhr.timeoff.LeaveRequestRepository;
import com.caderly.caderlyhr.timeoff.LeaveType;
import com.caderly.caderlyhr.timeoff.LeaveTypeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CLAUDE.md §5 rule 8: every new query gets a test proving a cross-tenant read comes back empty.
 * {@code people.PeopleFacadeImplTest}/{@code timeoff.TimeoffFacadeImplTest} already prove the
 * three new facade methods' filtering logic against a single tenant — this proves the three brand
 * new queries behind them ({@code EmployeeRepository#findForReport}, {@code
 * LeaveRequestRepository#summarizeUtilization}, {@code EmployeeStatusHistoryRepository
 * #countActiveByDepartmentAsOf}) stay inside Hibernate's {@code @TenantId}/RLS boundary end to end
 * through {@link ReportService}, the same way every other new query in this codebase is proven.
 */
class ReportServiceTenantIsolationTest extends TenantIsolationTestBase {

    @Autowired private ReportService reportService;
    @Autowired private EmployeeRepository employees;
    @Autowired private DivisionRepository divisions;
    @Autowired private DepartmentRepository departments;
    @Autowired private LeaveTypeRepository leaveTypes;
    @Autowired private LeaveBalanceRepository leaveBalances;
    @Autowired private LeaveRequestRepository leaveRequests;
    @Autowired private EmployeeStatusHistoryRepository statusHistory;

    @Test
    void leaveBalanceReport_underTenantA_neverIncludesTenantBsEmployees() {
        LeaveType typeA = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        LeaveType typeB = asTenant(tenantB, () -> saveLeaveType("Vacation"));
        Employee employeeA = asTenant(tenantA, () -> saveEmployee("Alpha", "Tenant"));
        Employee employeeB = asTenant(tenantB, () -> saveEmployee("Bravo", "Tenant"));
        asTenant(tenantA, () -> leaveBalances.save(LeaveBalance.grant(employeeA.requireId(), typeA, 2026, new BigDecimal("20"))));
        asTenant(tenantB, () -> leaveBalances.save(LeaveBalance.grant(employeeB.requireId(), typeB, 2026, new BigDecimal("20"))));

        List<ReportService.LeaveBalanceRow> result =
                asTenant(tenantA, () -> reportService.leaveBalanceReport(null, null, null, null, 2026));

        assertThat(result).extracting(ReportService.LeaveBalanceRow::employeeName)
                .contains("Alpha Tenant")
                .doesNotContain("Bravo Tenant");
    }

    @Test
    void leaveUtilizationReport_underTenantA_neverIncludesTenantBsRequests() {
        LeaveType typeA = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        LeaveType typeB = asTenant(tenantB, () -> saveLeaveType("Vacation"));
        Employee employeeA = asTenant(tenantA, () -> saveEmployee("Alpha", "Tenant"));
        Employee employeeB = asTenant(tenantB, () -> saveEmployee("Bravo", "Tenant"));
        asTenant(
                tenantA,
                () -> saveApprovedRequest(employeeA.requireId(), typeA, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)));
        asTenant(
                tenantB,
                () -> saveApprovedRequest(employeeB.requireId(), typeB, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)));

        List<ReportService.UtilizationRow> result =
                asTenant(
                        tenantA,
                        () ->
                                reportService.leaveUtilizationReport(
                                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null));

        assertThat(result).extracting(ReportService.UtilizationRow::employeeName)
                .contains("Alpha Tenant")
                .doesNotContain("Bravo Tenant");
    }

    @Test
    void headcountReport_underTenantA_neverIncludesTenantBsDepartments() {
        Division divisionA = asTenant(tenantA, () -> divisions.save(Division.create(uniqueName("DivA"), null)));
        Division divisionB = asTenant(tenantB, () -> divisions.save(Division.create(uniqueName("DivB"), null)));
        Department deptA = asTenant(tenantA, () -> departments.save(Department.create(uniqueName("DeptA"), null, divisionA)));
        Department deptB = asTenant(tenantB, () -> departments.save(Department.create(uniqueName("DeptB"), null, divisionB)));
        Employee employeeA = asTenant(tenantA, () -> saveEmployee("Alpha", "Tenant", deptA));
        Employee employeeB = asTenant(tenantB, () -> saveEmployee("Bravo", "Tenant", deptB));
        asTenant(
                tenantA,
                () ->
                        statusHistory.save(
                                EmployeeStatusHistory.open(
                                        employeeA, EmployeeStatus.ACTIVE, EmploymentType.FULL_TIME, LocalDate.of(2026, 1, 1))));
        asTenant(
                tenantB,
                () ->
                        statusHistory.save(
                                EmployeeStatusHistory.open(
                                        employeeB, EmployeeStatus.ACTIVE, EmploymentType.FULL_TIME, LocalDate.of(2026, 1, 1))));

        List<ReportService.HeadcountRow> result =
                asTenant(
                        tenantA,
                        () ->
                                reportService.headcountReport(
                                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null));

        assertThat(result).extracting(ReportService.HeadcountRow::departmentName)
                .contains(deptA.name())
                .doesNotContain(deptB.name());
    }

    private Employee saveEmployee(String firstName, String lastName) {
        return saveEmployee(firstName, lastName, null);
    }

    private Employee saveEmployee(String firstName, String lastName, com.caderly.caderlyhr.org.Department department) {
        Employee employee = Employee.create(firstName, lastName, "person-" + UUID.randomUUID() + "@example.test");
        employee.updateAdminFields(
                null, firstName, lastName, employee.email(), null, null, null, null, null, null, null, null,
                department, null, null, BigDecimal.valueOf(8.0), null, null);
        return employees.save(employee);
    }

    private LeaveType saveLeaveType(String name) {
        return leaveTypes.save(
                LeaveType.create(
                        name, "bi-airplane", "#0d6efd", true, true, false, true, new BigDecimal("20"), null));
    }

    private void saveApprovedRequest(UUID employeeId, LeaveType type, LocalDate start, LocalDate end) {
        LeaveRequest request =
                LeaveRequest.submit(employeeId, type, start, end, false, false, new BigDecimal("1.00"), null, Instant.now());
        request.approve(null, null, Instant.now());
        leaveRequests.save(request);
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
