package com.helyx.helyxhr.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.ValidationException;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Division/Department CRUD and the delete-or-archive rule (PRD §13.2). Extends {@link
 * TenantIsolationTestBase} directly rather than mocking the repositories, matching this
 * codebase's convention (see {@code InviteAndResetServiceTest}) of exercising services against a
 * real Testcontainers Postgres so the RLS/@TenantId plumbing is proven along the way, not
 * assumed.
 */
class OrganizationServiceTest extends TenantIsolationTestBase {

    @Autowired private DivisionService divisions;
    @Autowired private DepartmentService departments;

    @Test
    void createDivision_thenRename_preservesIdentity() {
        UUID id =
                asTenant(tenantA, () -> divisions.create("Engineering", "Builds the product").requireId());

        UUID sameId = asTenant(tenantA, () -> divisions.edit(id, "Product Engineering", null).requireId());

        assertThat(sameId).isEqualTo(id);
        Division reloaded = asTenant(tenantA, () -> divisions.require(id));
        assertThat(reloaded.name()).isEqualTo("Product Engineering");
        assertThat(reloaded.description()).isNull();
    }

    @Test
    void createDivision_withDuplicateNameInSameTenant_throwsConflict() {
        asTenant(tenantA, () -> divisions.create("Engineering", null));

        assertThatThrownBy(() -> asTenant(tenantA, () -> divisions.create("Engineering", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createDivision_withSameNameInAnotherTenant_succeeds() {
        // Uniqueness is per tenant (UNIQUE (tenant_id, name)), same as every other tenant-scoped
        // table — two companies may both have an "Engineering" division.
        asTenant(tenantA, () -> divisions.create("Engineering", null));

        UUID inTenantB = asTenant(tenantB, () -> divisions.create("Engineering", null).requireId());

        assertThat(inTenantB).isNotNull();
    }

    @Test
    void deleteOrArchiveDivision_withNoDepartments_hardDeletes() {
        UUID id = asTenant(tenantA, () -> divisions.create("Sales", null).requireId());

        DeleteOutcome outcome = asTenant(tenantA, () -> divisions.deleteOrArchive(id));

        assertThat(outcome).isEqualTo(DeleteOutcome.DELETED);
        assertThatThrownBy(() -> asTenant(tenantA, () -> divisions.require(id)))
                .isInstanceOf(com.helyx.helyxhr.common.NotFoundException.class);
    }

    @Test
    void deleteOrArchiveDivision_withActiveDepartment_archivesInstead() {
        UUID divisionId = asTenant(tenantA, () -> divisions.create("Engineering", null).requireId());
        asTenant(tenantA, () -> departments.create("Backend", null, divisionId));

        DeleteOutcome outcome = asTenant(tenantA, () -> divisions.deleteOrArchive(divisionId));

        assertThat(outcome).isEqualTo(DeleteOutcome.ARCHIVED);
        Division archived = asTenant(tenantA, () -> divisions.require(divisionId));
        assertThat(archived.archived()).isTrue();
        assertThat(asTenant(tenantA, divisions::listActive)).extracting(Division::getId).doesNotContain(divisionId);
    }

    @Test
    void createDepartment_assignsToDivisionAndAppearsInListing() {
        UUID divisionId = asTenant(tenantA, () -> divisions.create("Engineering", null).requireId());

        UUID departmentId =
                asTenant(tenantA, () -> departments.create("Backend", "APIs", divisionId).requireId());

        Department department = asTenant(tenantA, () -> departments.require(departmentId));
        assertThat(department.division().getId()).isEqualTo(divisionId);
        assertThat(asTenant(tenantA, departments::listActive))
                .extracting(Department::getId)
                .contains(departmentId);
    }

    @Test
    void createDepartment_underArchivedDivision_isRejected() {
        // Archive a division that already has a department (deleteOrArchive falls back to
        // archiving since it's in use), then try to add a second department to it.
        UUID activeDivisionId = asTenant(tenantA, () -> divisions.create("Ops", null).requireId());
        asTenant(tenantA, () -> departments.create("Facilities", null, activeDivisionId));
        asTenant(tenantA, () -> divisions.deleteOrArchive(activeDivisionId)); // archives (has a department)

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> departments.create("Security", null, activeDivisionId)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void editDepartment_movesToAnotherDivision() {
        UUID divisionA = asTenant(tenantA, () -> divisions.create("Engineering", null).requireId());
        UUID divisionB = asTenant(tenantA, () -> divisions.create("Product", null).requireId());
        UUID departmentId =
                asTenant(tenantA, () -> departments.create("Design", null, divisionA).requireId());

        asTenant(tenantA, () -> departments.edit(departmentId, "Design", null, divisionB));

        Department moved = asTenant(tenantA, () -> departments.require(departmentId));
        assertThat(moved.division().getId()).isEqualTo(divisionB);
    }

    @Test
    void deleteOrArchiveDepartment_withNoActiveEmployees_hardDeletes() {
        UUID divisionId = asTenant(tenantA, () -> divisions.create("Engineering", null).requireId());
        UUID departmentId =
                asTenant(tenantA, () -> departments.create("Backend", null, divisionId).requireId());

        DeleteOutcome outcome =
                asTenant(tenantA, () -> departments.deleteOrArchive(departmentId, false));

        assertThat(outcome).isEqualTo(DeleteOutcome.DELETED);
        assertThatThrownBy(() -> asTenant(tenantA, () -> departments.require(departmentId)))
                .isInstanceOf(com.helyx.helyxhr.common.NotFoundException.class);
    }

    @Test
    void deleteOrArchiveDepartment_withActiveEmployees_archivesInstead() {
        // The employee count itself is computed by web.AdminOrganizationController via
        // people.PeopleFacade (PRD §6.4 FR-4.2) — this service only implements the branch once
        // told the answer, which is exactly what this test drives directly.
        UUID divisionId = asTenant(tenantA, () -> divisions.create("Engineering", null).requireId());
        UUID departmentId =
                asTenant(tenantA, () -> departments.create("Backend", null, divisionId).requireId());

        DeleteOutcome outcome = asTenant(tenantA, () -> departments.deleteOrArchive(departmentId, true));

        assertThat(outcome).isEqualTo(DeleteOutcome.ARCHIVED);
        Department archived = asTenant(tenantA, () -> departments.require(departmentId));
        assertThat(archived.archived()).isTrue();
    }

    @Test
    void createDepartment_withDuplicateNameInSameTenant_throwsConflict() {
        UUID divisionId = asTenant(tenantA, () -> divisions.create("Engineering", null).requireId());
        asTenant(tenantA, () -> departments.create("Backend", null, divisionId));

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> departments.create("Backend", null, divisionId)))
                .isInstanceOf(ConflictException.class);
    }
}
