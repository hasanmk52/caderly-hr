package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.identity.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PeopleFacadeImpl implements PeopleFacade {

    private final EmployeeRepository employees;
    private final AppUserRepository appUsers;

    PeopleFacadeImpl(EmployeeRepository employees, AppUserRepository appUsers) {
        this.employees = employees;
        this.appUsers = appUsers;
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveEmployeesInDepartment(UUID departmentId) {
        return employees.countByDepartmentIdAndStatusNot(departmentId, EmployeeStatus.TERMINATED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeHireInfo> listActiveEmployeeHireInfo() {
        return employees.findAllByStatusNot(EmployeeStatus.TERMINATED).stream()
                .map(e -> new EmployeeHireInfo(e.requireId(), e.hireDate()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeHireInfo requireEmployeeHireInfo(UUID employeeId) {
        Employee employee =
                employees
                        .findById(employeeId)
                        .orElseThrow(
                                () -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
        return new EmployeeHireInfo(employee.requireId(), employee.hireDate());
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeApprovalInfo requireEmployeeApprovalInfo(UUID employeeId) {
        Employee employee =
                employees
                        .findById(employeeId)
                        .orElseThrow(
                                () -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
        return toApprovalInfo(employee);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeApprovalInfo> listActiveAdminApprovalInfo() {
        return appUsers.findAllByStatus(UserStatus.ACTIVE).stream()
                .filter(user -> user.roles().contains(Role.ADMIN))
                .map(AppUser::requireId)
                .map(employees::findByUserId)
                .flatMap(Optional::stream)
                .map(PeopleFacadeImpl::toApprovalInfo)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isManagerOf(UUID managerId, UUID employeeId) {
        return employees.isManagerOf(managerId, employeeId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findEmployeeIdByUserId(UUID userId) {
        return employees.findByUserId(userId).map(Employee::requireId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeCalendarInfo> listEmployeesForCalendar(
            @Nullable UUID departmentId, @Nullable UUID divisionId) {
        return employees.findActiveForCalendar(departmentId, divisionId).stream()
                .map(PeopleFacadeImpl::toCalendarInfo)
                .toList();
    }

    private static EmployeeApprovalInfo toApprovalInfo(Employee employee) {
        UUID managerId = employee.manager() == null ? null : employee.manager().requireId();
        return new EmployeeApprovalInfo(employee.requireId(), employee.fullName(), employee.email(), managerId);
    }

    private static EmployeeCalendarInfo toCalendarInfo(Employee employee) {
        String departmentName = employee.department() == null ? null : employee.department().name();
        return new EmployeeCalendarInfo(employee.requireId(), employee.fullName(), departmentName);
    }
}
