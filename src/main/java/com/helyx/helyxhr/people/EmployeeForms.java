package com.helyx.helyxhr.people;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Form-backing records for Employee create/edit (CLAUDE.md §7: DTOs are records).
 *
 * <p>Unlike {@code web.OrganizationForms} (2-4 fields, unpacked into primitive service-method
 * parameters), these records are passed through to {@link EmployeeService} as-is — with ~15
 * optional fields each, positional primitive parameters of the same type would be a correctness
 * hazard (easy to transpose two strings), and the record's field names are the guard against
 * that. Declared here rather than in {@code web} so {@code EmployeeService} can reference them
 * directly; both {@code web.AdminEmployeeController} and {@code web.ProfileController} bind
 * requests to these same types.
 *
 * <p>{@link SelfProfilePatch} and {@link AdminEmployeePatch} are deliberately separate types, not
 * one DTO with a role-filtered subset: Spring's binding makes it structurally impossible for a
 * self-service request to carry a job/compensation field (CLAUDE.md's "enforced by construction"
 * principle, not a runtime allowlist).
 */
public final class EmployeeForms {

    private EmployeeForms() {}

    public record CreateEmployee(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Email @Size(max = 255) String email,
            @Nullable @Size(max = 50) String employeeCode,
            @Nullable @Size(max = 30) String phone,
            @Nullable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birthDate,
            @Nullable @Size(max = 20) String gender,
            @Nullable @Size(max = 20) String maritalStatus,
            @Nullable @Size(max = 100) String nationality,
            @Nullable @Size(max = 100) String citizenship,
            @Nullable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hireDate,
            @Nullable EmploymentType employmentType,
            @Nullable UUID departmentId,
            @Nullable UUID managerId,
            @Nullable @Size(max = 150) String jobTitle,
            @Nullable @Size(max = 150) String workLocation,
            @Nullable @DecimalMin("0.0") @DecimalMax("24.0") BigDecimal workingHoursPerDay,
            @Nullable @Size(max = 3) String currency,
            @Nullable String baseCompensation) {

        AdminEmployeePatch toAdminPatch() {
            return new AdminEmployeePatch(
                    employeeCode,
                    firstName,
                    lastName,
                    email,
                    phone,
                    birthDate,
                    gender,
                    maritalStatus,
                    nationality,
                    citizenship,
                    hireDate,
                    employmentType,
                    departmentId,
                    managerId,
                    jobTitle,
                    workLocation,
                    workingHoursPerDay,
                    currency,
                    baseCompensation);
        }
    }

    public record AdminEmployeePatch(
            @Nullable @Size(max = 50) String employeeCode,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Email @Size(max = 255) String email,
            @Nullable @Size(max = 30) String phone,
            @Nullable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birthDate,
            @Nullable @Size(max = 20) String gender,
            @Nullable @Size(max = 20) String maritalStatus,
            @Nullable @Size(max = 100) String nationality,
            @Nullable @Size(max = 100) String citizenship,
            @Nullable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hireDate,
            @Nullable EmploymentType employmentType,
            @Nullable UUID departmentId,
            @Nullable UUID managerId,
            @Nullable @Size(max = 150) String jobTitle,
            @Nullable @Size(max = 150) String workLocation,
            @Nullable @DecimalMin("0.0") @DecimalMax("24.0") BigDecimal workingHoursPerDay,
            @Nullable @Size(max = 3) String currency,
            @Nullable String baseCompensation) {}

    public record SelfProfilePatch(
            @Nullable @Size(max = 30) String phone,
            @Nullable @Size(max = 200) String addressLine1,
            @Nullable @Size(max = 200) String addressLine2,
            @Nullable @Size(max = 100) String city,
            @Nullable @Size(max = 100) String country,
            @Nullable @Size(max = 20) String postalCode) {}

    public record TerminateEmployee(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate terminationDate) {}
}
