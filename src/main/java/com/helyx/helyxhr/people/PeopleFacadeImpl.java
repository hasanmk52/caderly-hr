package com.helyx.helyxhr.people;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PeopleFacadeImpl implements PeopleFacade {

    private final EmployeeRepository employees;

    PeopleFacadeImpl(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveEmployeesInDepartment(UUID departmentId) {
        return employees.countByDepartmentIdAndStatusNot(departmentId, EmployeeStatus.TERMINATED);
    }
}
