package com.helyx.helyxhr.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Form-backing records for the Divisions/Departments admin page (CLAUDE.md §7: DTOs are
 * records). Create and edit share the same shape, so one record covers both — the id being
 * edited (if any) travels separately, as a path variable.
 */
final class OrganizationForms {

    private OrganizationForms() {}

    record DivisionForm(@NotBlank @Size(max = 150) String name, String description) {}

    record DepartmentForm(
            @NotBlank @Size(max = 150) String name, String description, @NotNull UUID divisionId) {}
}
