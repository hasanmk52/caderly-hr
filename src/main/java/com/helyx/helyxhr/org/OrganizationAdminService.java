package com.helyx.helyxhr.org;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary for the Divisions/Departments admin page (PRD §13). {@code
 * AdminOrganizationController} needs a write followed immediately by a fresh read of both
 * Divisions and Departments (to re-render the table), and read-only pages that touch both
 * entities in one request. Doing each of those as separate, unrelated transactions was observed
 * to intermittently render an empty table right after a genuinely-committed write — see ADR
 * 0007. Every method here is exactly one transaction covering everything a single controller
 * response needs, which closes that gap.
 *
 * <p>CLAUDE.md §7 keeps {@code @Transactional} out of controllers, so this is where it lives
 * instead: {@code DivisionService}/{@code DepartmentService} still own their own write
 * transactions individually (each is still independently correct and independently testable);
 * this class only adds the "and read everything back in the same transaction" step a page
 * render needs. None of the exceptions the two services throw are caught here — they propagate
 * straight to the controller, so there's no rollback-only trap to work around (see ADR 0007).
 */
@Service
public class OrganizationAdminService {

    private final DivisionService divisions;
    private final DepartmentService departments;

    OrganizationAdminService(DivisionService divisions, DepartmentService departments) {
        this.divisions = divisions;
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public OrganizationSnapshot snapshot() {
        return new OrganizationSnapshot(divisions.listActive(), departments.listActive());
    }

    @Transactional(readOnly = true)
    public DepartmentEditData departmentEditData(UUID id) {
        return new DepartmentEditData(departments.require(id), divisions.listActive());
    }

    @Transactional
    public OrganizationSnapshot createDivision(String name, @Nullable String description) {
        divisions.create(name, description);
        return snapshot();
    }

    @Transactional
    public OrganizationSnapshot editDivision(UUID id, String name, @Nullable String description) {
        divisions.edit(id, name, description);
        return snapshot();
    }

    @Transactional
    public DeleteResult deleteDivision(UUID id) {
        DeleteOutcome outcome = divisions.deleteOrArchive(id);
        return new DeleteResult(outcome, snapshot());
    }

    @Transactional
    public OrganizationSnapshot createDepartment(
            String name, @Nullable String description, UUID divisionId) {
        departments.create(name, description, divisionId);
        return snapshot();
    }

    @Transactional
    public OrganizationSnapshot editDepartment(
            UUID id, String name, @Nullable String description, UUID divisionId) {
        departments.edit(id, name, description, divisionId);
        return snapshot();
    }

    @Transactional
    public DeleteResult deleteDepartment(UUID id) {
        DeleteOutcome outcome = departments.delete(id);
        return new DeleteResult(outcome, snapshot());
    }

    public record OrganizationSnapshot(List<Division> divisions, List<Department> departments) {}

    public record DepartmentEditData(Department department, List<Division> divisionOptions) {}

    public record DeleteResult(DeleteOutcome outcome, OrganizationSnapshot snapshot) {}
}
