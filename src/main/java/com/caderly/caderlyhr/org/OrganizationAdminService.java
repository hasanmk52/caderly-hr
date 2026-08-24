package com.caderly.caderlyhr.org;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary for {@link com.caderly.caderlyhr.web.AdminOrganizationController}'s
 * write-then-read and multi-read requests (PRD §13). Splitting a write from its follow-up table
 * read into separate transactions let a genuinely-committed write render as an empty table (ADR
 * 0007); each method here is one transaction covering everything a single controller response
 * needs.
 *
 * <p>Keeps {@code @Transactional} out of the controller (CLAUDE.md §7). Nothing here catches the
 * {@code CaderlyException}s {@code DivisionService}/{@code DepartmentService} throw, so they
 * propagate cleanly with no rollback-only trap to work around.
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
    public DeleteResult deleteDepartment(UUID id, boolean hasActiveEmployees) {
        DeleteOutcome outcome = departments.deleteOrArchive(id, hasActiveEmployees);
        return new DeleteResult(outcome, snapshot());
    }

    public record OrganizationSnapshot(List<Division> divisions, List<Department> departments) {}

    public record DepartmentEditData(Department department, List<Division> divisionOptions) {}

    public record DeleteResult(DeleteOutcome outcome, OrganizationSnapshot snapshot) {}
}
