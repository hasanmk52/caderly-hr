package com.caderly.caderlyhr.web;

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
            @NotNull(message = "{validation.book-leave-form.leave-type-id.required}") UUID leaveTypeId,
            @NotNull(message = "{validation.book-leave-form.start-date.required}")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @NotNull(message = "{validation.book-leave-form.end-date.required}")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate endDate,
            boolean startHalfDayPm,
            boolean endHalfDayAm,
            @Nullable @Size(max = 1000, message = "{validation.book-leave-form.note.too-long}") String note) {}

    record RejectForm(
            @Nullable @Size(max = 500, message = "{validation.reject-form.decision-note.too-long}")
                    String decisionNote) {}
}
