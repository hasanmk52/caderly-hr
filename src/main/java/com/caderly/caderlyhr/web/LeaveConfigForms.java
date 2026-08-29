package com.caderly.caderlyhr.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

/** Form-backing records for the Leave Types / Holidays admin pages (CLAUDE.md §7: DTOs are records). */
final class LeaveConfigForms {

    private LeaveConfigForms() {}

    record LeaveTypeForm(
            @NotBlank(message = "{validation.leave-type-form.name.required}")
                    @Size(max = 100, message = "{validation.leave-type-form.name.too-long}")
                    String name,
            @Nullable @Size(max = 50, message = "{validation.leave-type-form.icon.too-long}") String icon,
            @Nullable @Size(max = 7, message = "{validation.leave-type-form.color.too-long}") String color,
            boolean paid,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            boolean requiresApproval,
            @NotNull(message = "{validation.leave-type-form.default-annual-allowance.required}")
                    @DecimalMin(value = "0.0", message = "{validation.leave-type-form.default-annual-allowance.negative}")
                    BigDecimal defaultAnnualAllowance,
            @Nullable String description) {}

    record HolidayForm(
            @NotNull(message = "{validation.holiday-form.date.required}")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date,
            @NotBlank(message = "{validation.holiday-form.name.required}")
                    @Size(max = 200, message = "{validation.holiday-form.name.too-long}")
                    String name) {}

    /** Backs the manual balance adjustment endpoint (PRD §12.2's "required reason"). */
    record BalanceAdjustmentForm(
            @NotNull(message = "{validation.balance-adjustment-form.new-granted.required}")
                    @DecimalMin(value = "0.0", message = "{validation.balance-adjustment-form.new-granted.negative}")
                    BigDecimal newGranted,
            @NotBlank(message = "{validation.balance-adjustment-form.reason.required}")
                    @Size(max = 500, message = "{validation.balance-adjustment-form.reason.too-long}")
                    String reason) {}
}
