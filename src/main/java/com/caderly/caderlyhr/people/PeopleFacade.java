package com.caderly.caderlyhr.people;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of Employee data for other modules (CLAUDE.md §4). Original consumer is {@code
 * web.AdminOrganizationController}, which needs an employee count to decide whether deleting a
 * Department should archive it instead (PRD §6.4 FR-4.2, the guard 1.3 deferred). {@code timeoff}
 * is the second consumer (Phase 1.5) — its balance-grant jobs need to iterate active employees;
 * {@code timeoff} never depends back on {@code people} for anything else, and {@code people}
 * never imports {@code timeoff} at all (see {@link EmployeeHiredEvent}).
 *
 * <p>Deliberately not consumed by {@code org} itself: {@code org} already flows into {@code
 * people} via {@code OrgFacade}, so {@code org} depending back on {@code people} would be a
 * package cycle ArchUnit rejects. The orchestration lives one layer up instead.
 */
public interface PeopleFacade {

    /** Employees in this department whose status isn't TERMINATED. */
    long countActiveEmployeesInDepartment(UUID departmentId);

    /**
     * Employees whose status isn't TERMINATED, for {@code timeoff.BalanceService}'s annual grant
     * job and leave-type-activation backfill (PRD §12.2). {@code hireDate} is nullable exactly as
     * it is on {@link Employee} itself — callers must decide what to do with an employee who has
     * none.
     */
    List<EmployeeHireInfo> listActiveEmployeeHireInfo();

    /**
     * @throws com.caderly.caderlyhr.common.NotFoundException if the employee doesn't exist. Backs the
     *     on-hire grant ({@link EmployeeHiredEvent}).
     */
    EmployeeHireInfo requireEmployeeHireInfo(UUID employeeId);

    /**
     * Approval routing info for {@code timeoff.LeaveRequestService} (PRD §12.4 steps 2-3):
     * booking looks up the requester's own row (for {@code managerId}) and, when routing to a
     * manager, the manager's own row (for name/email to put in the notification).
     *
     * @throws com.caderly.caderlyhr.common.NotFoundException if the employee doesn't exist.
     */
    EmployeeApprovalInfo requireEmployeeApprovalInfo(UUID employeeId);

    /**
     * Every ACTIVE login holding {@code Role.ADMIN}, resolved to their linked Employee (PRD §12.4
     * step 2's "Admin(s)" fallback when a requester has no manager). An Admin login with no linked
     * Employee (e.g. a dev-bootstrap account) is silently skipped — there is no employee identity
     * to notify or to check {@code isManagerOf} against.
     */
    List<EmployeeApprovalInfo> listActiveAdminApprovalInfo();

    /**
     * Whether {@code managerId} is a direct or indirect manager of {@code employeeId} (PRD §26:
     * Manager approval authority is transitive). Delegates straight to {@code
     * EmployeeRepository#isManagerOf}, the same recursive-CTE query {@code
     * EmployeeService#getProfileForViewer} already relies on.
     */
    boolean isManagerOf(UUID managerId, UUID employeeId);

    record EmployeeHireInfo(UUID employeeId, @Nullable LocalDate hireDate) {}

    record EmployeeApprovalInfo(
            UUID employeeId, String fullName, String email, @Nullable UUID managerId) {}
}
