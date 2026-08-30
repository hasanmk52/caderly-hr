package com.caderly.caderlyhr.timeoff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of approved leave and public holidays for other modules (CLAUDE.md §4). First
 * consumer is {@code calendar} (sub-phase 1.8): the team calendar grid and the per-user iCal feed
 * both need approved leave without any of {@code timeoff}'s write-side state machine or balance
 * math.
 */
public interface TimeoffFacade {

    /**
     * APPROVED leave for the given employees overlapping [from, to] (inclusive), optionally
     * narrowed to one leave type — the team calendar grid's query (PRD §6.6 FR-6.1/FR-6.2).
     */
    List<ApprovedLeaveEntry> listApprovedLeaveInRange(
            LocalDate from, LocalDate to, List<UUID> employeeIds, @Nullable UUID leaveTypeId);

    /**
     * Every APPROVED leave request for one employee, unbounded — the iCal feed's full scope (PRD
     * AC-CALENDAR.1: "all my APPROVED leaves").
     */
    List<ApprovedLeaveEntry> listAllApprovedLeaveForEmployee(UUID employeeId);

    /** Public holidays overlapping [from, to] — the grid's shaded holiday columns (UI §8.4). */
    List<HolidayMarker> listPublicHolidaysInRange(LocalDate from, LocalDate to);

    record ApprovedLeaveEntry(
            UUID employeeId,
            UUID leaveRequestId,
            String leaveTypeName,
            @Nullable String leaveTypeColor,
            @Nullable String leaveTypeIcon,
            LocalDate startDate,
            LocalDate endDate,
            boolean startHalfDayPm,
            boolean endHalfDayAm,
            BigDecimal durationDays) {}

    record HolidayMarker(LocalDate date, String name) {}
}
