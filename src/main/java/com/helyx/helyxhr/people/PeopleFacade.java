package com.helyx.helyxhr.people;

import java.util.UUID;

/**
 * Read-only view of Employee data for other modules (CLAUDE.md §4). Today's only consumer is
 * {@code web.AdminOrganizationController}, which needs an employee count to decide whether
 * deleting a Department should archive it instead (PRD §6.4 FR-4.2, the guard 1.3 deferred).
 *
 * <p>Deliberately not consumed by {@code org} itself: {@code org} already flows into {@code
 * people} via {@code OrgFacade}, so {@code org} depending back on {@code people} would be a
 * package cycle ArchUnit rejects. The orchestration lives one layer up instead.
 */
public interface PeopleFacade {

    /** Employees in this department whose status isn't TERMINATED. */
    long countActiveEmployeesInDepartment(UUID departmentId);
}
