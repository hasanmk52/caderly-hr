package com.helyx.helyxhr.org;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.NotFoundException;
import com.helyx.helyxhr.common.ValidationException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Department CRUD (PRD §13.2). */
@Service
public class DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

    private final DepartmentRepository departments;
    private final DivisionRepository divisions;

    DepartmentService(DepartmentRepository departments, DivisionRepository divisions) {
        this.departments = departments;
        this.divisions = divisions;
    }

    @Transactional(readOnly = true)
    public List<Department> listActive() {
        return departments.findAllByArchivedFalseOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Department require(UUID id) {
        return departments
                .findById(id)
                .orElseThrow(
                        () -> new NotFoundException("DEPARTMENT_NOT_FOUND", "Department not found"));
    }

    @Transactional
    public Department create(String name, @Nullable String description, UUID divisionId) {
        if (departments.existsByNameIgnoreCase(name)) {
            throw new ConflictException(
                    "DEPARTMENT_NAME_TAKEN", "A department named \"" + name + "\" already exists");
        }
        Division division = requireActiveDivision(divisionId);
        Department saved = departments.save(Department.create(name, description, division));
        log.info("Created department {}", saved.requireId());
        return saved;
    }

    @Transactional
    public Department edit(UUID id, String name, @Nullable String description, UUID divisionId) {
        Department department = require(id);
        if (departments.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException(
                    "DEPARTMENT_NAME_TAKEN", "A department named \"" + name + "\" already exists");
        }
        Division division = requireActiveDivision(divisionId);
        department.rename(name);
        department.updateDescription(description);
        department.moveToDivision(division);
        return department;
    }

    /**
     * PRD §6.4 FR-4.2: hard delete only when the department has no active employees, otherwise
     * archive. {@code hasActiveEmployees} arrives pre-computed rather than being queried here
     * because the answer lives in {@code people}, and {@code org} must not depend on it — that
     * would cycle against {@code people}'s own dependency on {@code org} via {@code OrgFacade}.
     * {@code web.AdminOrganizationController} is the orchestration layer that asks {@code
     * people.PeopleFacade} first and passes the answer in (see its Javadoc).
     */
    @Transactional
    public DeleteOutcome deleteOrArchive(UUID id, boolean hasActiveEmployees) {
        Department department = require(id);
        if (hasActiveEmployees) {
            department.archive();
            log.info("Archived department {} (has active employees)", id);
            return DeleteOutcome.ARCHIVED;
        }
        departments.delete(department);
        log.info("Deleted department {}", id);
        return DeleteOutcome.DELETED;
    }

    private Division requireActiveDivision(UUID divisionId) {
        Division division =
                divisions
                        .findById(divisionId)
                        .orElseThrow(
                                () -> new NotFoundException("DIVISION_NOT_FOUND", "Division not found"));
        if (division.archived()) {
            throw new ValidationException(
                    "DIVISION_ARCHIVED", "Cannot assign a department to an archived division");
        }
        return division;
    }
}
