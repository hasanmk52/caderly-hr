package com.caderly.caderlyhr.calendar;

import com.caderly.caderlyhr.people.PeopleFacade;
import com.caderly.caderlyhr.people.PeopleFacade.EmployeeCalendarInfo;
import com.caderly.caderlyhr.timeoff.TimeoffFacade;
import com.caderly.caderlyhr.timeoff.TimeoffFacade.ApprovedLeaveEntry;
import com.caderly.caderlyhr.timeoff.TimeoffFacade.HolidayMarker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the team calendar grid (PRD §6.6 FR-6.1/FR-6.2, UI Guidelines §8.4): one row per
 * not-terminated employee (optionally filtered by department/division), each carrying the leave
 * "bars" that overlap the visible range (optionally filtered by leave type), plus the visible
 * range's public holidays for the grid's shaded columns.
 *
 * <p>Returns bars, not a literal per-day matrix — the template positions each bar with a CSS grid
 * column start/span, matching UI Guidelines §8.4's "rounded bar spanning start-to-end" rather than
 * building a day-by-day cell grid server-side.
 */
@Service
public class CalendarService {

    private final PeopleFacade people;
    private final TimeoffFacade timeoff;

    CalendarService(PeopleFacade people, TimeoffFacade timeoff) {
        this.people = people;
        this.timeoff = timeoff;
    }

    @Transactional(readOnly = true)
    public TeamCalendarView buildTeamCalendar(
            LocalDate from,
            LocalDate to,
            @Nullable UUID departmentId,
            @Nullable UUID divisionId,
            @Nullable UUID leaveTypeId) {
        List<EmployeeCalendarInfo> employees = people.listEmployeesForCalendar(departmentId, divisionId);
        List<UUID> employeeIds = employees.stream().map(EmployeeCalendarInfo::employeeId).toList();

        Map<UUID, List<ApprovedLeaveEntry>> leaveByEmployee =
                timeoff.listApprovedLeaveInRange(from, to, employeeIds, leaveTypeId).stream()
                        .collect(Collectors.groupingBy(ApprovedLeaveEntry::employeeId));

        List<EmployeeCalendarRow> rows =
                employees.stream()
                        .map(
                                employee ->
                                        new EmployeeCalendarRow(
                                                employee.employeeId(),
                                                employee.fullName(),
                                                employee.departmentName(),
                                                leaveByEmployee
                                                        .getOrDefault(employee.employeeId(), List.of())
                                                        .stream()
                                                        .map(CalendarService::toBar)
                                                        .toList()))
                        .toList();

        List<HolidayMarker> holidays = timeoff.listPublicHolidaysInRange(from, to);
        return new TeamCalendarView(rows, holidays);
    }

    private static LeaveBar toBar(ApprovedLeaveEntry entry) {
        return new LeaveBar(
                entry.leaveRequestId(),
                entry.leaveTypeName(),
                entry.leaveTypeIcon(),
                entry.startDate(),
                entry.endDate(),
                entry.startHalfDayPm(),
                entry.endHalfDayAm(),
                entry.durationDays());
    }

    public record TeamCalendarView(List<EmployeeCalendarRow> rows, List<HolidayMarker> holidays) {}

    public record EmployeeCalendarRow(
            UUID employeeId, String fullName, @Nullable String departmentName, List<LeaveBar> bars) {}

    /**
     * No color field: every bar renders with the same fixed brand-theme background/border
     * (CSS {@code .calendar-bar}), leave types are told apart only by their bold {@code icon}
     * glyph (see {@code CalendarController.boldIcon}) — a deliberate product decision, not an
     * oversight (an earlier per-leave-type color-hash version of this record was reverted).
     */
    public record LeaveBar(
            UUID leaveRequestId,
            String leaveTypeName,
            @Nullable String icon,
            LocalDate startDate,
            LocalDate endDate,
            boolean startHalfDayPm,
            boolean endHalfDayAm,
            BigDecimal durationDays) {}
}
