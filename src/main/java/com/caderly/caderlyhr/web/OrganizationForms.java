package com.caderly.caderlyhr.web;

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

    record DivisionForm(
            @NotBlank(message = "{validation.division-form.name.required}")
                    @Size(max = 150, message = "{validation.division-form.name.too-long}")
                    String name,
            String description) {}

    record DepartmentForm(
            @NotBlank(message = "{validation.department-form.name.required}")
                    @Size(max = 150, message = "{validation.department-form.name.too-long}")
                    String name,
            String description,
            @NotNull(message = "{validation.department-form.division-id.required}") UUID divisionId) {}
}
