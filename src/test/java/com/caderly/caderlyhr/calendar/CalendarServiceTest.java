package com.caderly.caderlyhr.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.calendar.CalendarService.TeamCalendarView;
import com.caderly.caderlyhr.org.Department;
import com.caderly.caderlyhr.org.DepartmentRepository;
import com.caderly.caderlyhr.org.Division;
import com.caderly.caderlyhr.org.DivisionRepository;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeRepository;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import com.caderly.caderlyhr.timeoff.LeaveRequest;
import com.caderly.caderlyhr.timeoff.LeaveRequestRepository;
import com.caderly.caderlyhr.timeoff.LeaveType;
import com.caderly.caderlyhr.timeoff.LeaveTypeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Grid composition (PRD §6.6 FR-6.1/FR-6.2): every not-terminated employee gets a row, whether or
 * not they have leave in range; leave bars and holidays come from {@code TimeoffFacade}, filtered
 * by the same department/division employee list.
 */
class CalendarServiceTest extends TenantIsolationTestBase {

    @Autowired private CalendarService calendarService;
    @Autowired private EmployeeRepository employees;
    @Autowired private DivisionRepository divisionRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private LeaveTypeRepository leaveTypes;
    @Autowired private LeaveRequestRepository leaveRequests;

    private static final LocalDate MONTH_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate MONTH_END = LocalDate.of(2026, 9, 30);

    @Test
    void buildTeamCalendar_includesEveryEmployeeEvenWithNoLeave() {
        Employee employee = asTenant(tenantA, () -> saveEmployee("No", "Leave", null));

        TeamCalendarView view =
                asTenant(tenantA, () -> calendarService.buildTeamCalendar(MONTH_START, MONTH_END, null, null, null));

        assertThat(view.rows()).anySatisfy(
                row -> {
                    assertThat(row.employeeId()).isEqualTo(employee.requireId());
                    assertThat(row.bars()).isEmpty();
                });
    }

    @Test
    void buildTeamCalendar_attachesApprovedLeaveBarsToTheirEmployeesRow() {
        LeaveType type = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        Employee employee = asTenant(tenantA, () -> saveEmployee("Has", "Leave", null));
        LeaveRequest approved =
                asTenant(
                        tenantA,
                        () ->
                                saveApprovedRequest(
                                        employee.requireId(), type, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12)));

        TeamCalendarView view =
                asTenant(tenantA, () -> calendarService.buildTeamCalendar(MONTH_START, MONTH_END, null, null, null));

        var row =
                view.rows().stream()
                        .filter(r -> r.employeeId().equals(employee.requireId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(row.bars()).extracting(CalendarService.LeaveBar::leaveRequestId)
                .containsExactly(approved.requireId());
    }

    @Test
    void buildTeamCalendar_filteredByDepartment_excludesEmployeesOutsideIt() {
        Division division = asTenant(tenantA, () -> divisionRepository.save(Division.create(uniqueName("Div"), null)));
        Department department =
                asTenant(tenantA, () -> departmentRepository.save(Department.create(uniqueName("Dept"), null, division)));
        Employee inDepartment = asTenant(tenantA, () -> saveEmployee("In", "Dept", department));
        asTenant(tenantA, () -> saveEmployee("Outside", "Dept", null));

        TeamCalendarView view =
                asTenant(
                        tenantA,
                        () -> calendarService.buildTeamCalendar(MONTH_START, MONTH_END, department.requireId(), null, null));

        assertThat(view.rows()).extracting(CalendarService.EmployeeCalendarRow::employeeId)
                .containsExactly(inDepartment.requireId());
    }

    @Test
    void buildTeamCalendar_includesHolidaysInTheVisibleRange() {
        // No employees needed to prove holidays come through independently of the employee list.
        asTenant(
                tenantA,
                () ->
                        leaveTypes.save(
                                LeaveType.create("placeholder", null, null, true, false, false, true, BigDecimal.ONE, null)));

        TeamCalendarView view =
                asTenant(tenantA, () -> calendarService.buildTeamCalendar(MONTH_START, MONTH_END, null, null, null));

        assertThat(view.holidays()).isNotNull();
    }

    private Employee saveEmployee(String firstName, String lastName, Department department) {
        Employee employee = Employee.create(firstName, lastName, "person-" + UUID.randomUUID() + "@example.test");
        employee.updateAdminFields(
                null, firstName, lastName, employee.email(), null, null, null, null, null, null, null, null,
                department, null, null, BigDecimal.valueOf(8.0), null, null);
        return employees.save(employee);
    }

    private LeaveType saveLeaveType(String name) {
        return leaveTypes.save(
                LeaveType.create(name, "bi-airplane", "#0d6efd", true, true, false, true, BigDecimal.TEN, null));
    }

    private LeaveRequest saveApprovedRequest(UUID employeeId, LeaveType type, LocalDate start, LocalDate end) {
        LeaveRequest request =
                LeaveRequest.submit(employeeId, type, start, end, false, false, new BigDecimal("1.00"), null, Instant.now());
        // decider_id has a real FK to app_user(id); null is allowed and simpler than seeding one.
        request.approve(null, null, Instant.now());
        return leaveRequests.save(request);
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
