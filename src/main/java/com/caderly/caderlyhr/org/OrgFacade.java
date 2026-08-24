package com.caderly.caderlyhr.org;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view of Divisions/Departments for other modules (CLAUDE.md §4: cross-module reads go
 * through a facade). {@code people} is the first consumer — the Employee create/edit forms'
 * department picker, and validating a chosen department exists. One-directional: {@code org}
 * never depends back on {@code people} (see {@code AdminOrganizationController}'s delete-guard
 * orchestration for why that direction is avoided).
 */
public interface OrgFacade {

    List<DepartmentOption> listActiveDepartments();

    List<DivisionOption> listActiveDivisions();

    /** @throws com.caderly.caderlyhr.common.NotFoundException if the department doesn't exist or is archived. */
    Department requireActiveDepartment(UUID id);

    record DepartmentOption(UUID id, String name, String divisionName) {}

    record DivisionOption(UUID id, String name) {}
}
