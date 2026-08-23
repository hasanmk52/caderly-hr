package com.helyx.helyxhr.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

/** Form-backing records for self-service booking and the For Action approval inbox (CLAUDE.md §7). */
final class LeaveForms {

    private LeaveForms() {}

    record BookLeaveForm(
            @NotNull(message = "Select a leave type") UUID leaveTypeId,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            boolean startHalfDayPm,
            boolean endHalfDayAm,
            @Nullable @Size(max = 1000) String note) {}

    record RejectForm(@Nullable @Size(max = 500) String decisionNote) {}
}
