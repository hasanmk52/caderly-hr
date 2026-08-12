package com.helyx.helyxhr.org;

import com.helyx.helyxhr.common.ValidationException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class OrgFacadeImpl implements OrgFacade {

    private final DivisionService divisions;
    private final DepartmentService departments;

    OrgFacadeImpl(DivisionService divisions, DepartmentService departments) {
        this.divisions = divisions;
        this.departments = departments;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentOption> listActiveDepartments() {
        return departments.listActive().stream()
                .map(d -> new DepartmentOption(d.requireId(), d.name(), d.division().name()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DivisionOption> listActiveDivisions() {
        return divisions.listActive().stream().map(d -> new DivisionOption(d.requireId(), d.name())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Department requireActiveDepartment(UUID id) {
        Department department = departments.require(id);
        if (department.archived()) {
            throw new ValidationException(
                    "DEPARTMENT_ARCHIVED", "Cannot assign an employee to an archived department");
        }
        return department;
    }
}
