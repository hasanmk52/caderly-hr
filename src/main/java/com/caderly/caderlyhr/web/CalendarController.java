package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.calendar.CalendarService;
import com.caderly.caderlyhr.calendar.CalendarService.EmployeeCalendarRow;
import com.caderly.caderlyhr.calendar.CalendarService.LeaveBar;
import com.caderly.caderlyhr.calendar.CalendarService.TeamCalendarView;
import com.caderly.caderlyhr.calendar.CalendarTokenService;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.org.OrgFacade;
import com.caderly.caderlyhr.timeoff.LeaveTypeService;
import com.caderly.caderlyhr.timeoff.TimeoffFacade.HolidayMarker;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Team calendar grid and Settings -> Calendar integration (PRD §6.6, §9.4 US-CAL.3, §24.5/§24.10,
 * sub-phase 1.8). Follows {@code FilesController}'s shape: {@code isAuthenticated()} at the class
 * level — PRD §26 gives Employee/Manager/Admin identical "view all" access to the team calendar,
 * so there is no row-level restriction to enforce here.
 *
 * <p>Month view only, no separate htmx fragment endpoint: the filter panel and month
 * navigation are a plain GET form/links, matching {@code AdminEmployeeController}'s
 * department/status filter convention rather than introducing a new htmx pattern for one page.
 * Week view and the Grid/List toggle named in PRD §24.5 are not built this phase — the DoD only
 * requires a filterable month grid, and both are separable additions later.
 */
@Controller
@PreAuthorize("isAuthenticated()")
class CalendarController {

    private final CalendarService calendarService;
    private final CalendarTokenService tokenService;
    private final OrgFacade org;
    private final LeaveTypeService leaveTypes;
    private final MessageSource messages;
    private final Clock clock;

    CalendarController(
            CalendarService calendarService,
            CalendarTokenService tokenService,
            OrgFacade org,
            LeaveTypeService leaveTypes,
            MessageSource messages,
            Clock clock) {
        this.calendarService = calendarService;
        this.tokenService = tokenService;
        this.org = org;
        this.leaveTypes = leaveTypes;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping("/calendar")
    @PreAuthorize("isAuthenticated()")
    String calendarPage(
            @RequestParam(required = false) @Nullable String month,
            @RequestParam(required = false) @Nullable UUID departmentId,
            @RequestParam(required = false) @Nullable UUID divisionId,
            @RequestParam(required = false) @Nullable UUID leaveTypeId,
            Model model) {
        YearMonth visibleMonth = parseMonth(month);
        LocalDate from = visibleMonth.atDay(1);
        LocalDate to = visibleMonth.atEndOfMonth();

        TeamCalendarView view =
                calendarService.buildTeamCalendar(from, to, departmentId, divisionId, leaveTypeId);

        int totalColumns = (int) ChronoUnit.DAYS.between(from, to) + 1;
        model.addAttribute("visibleMonth", visibleMonth);
        model.addAttribute("monthLabel", from);
        model.addAttribute("previousMonth", visibleMonth.minusMonths(1));
        model.addAttribute("nextMonth", visibleMonth.plusMonths(1));
        model.addAttribute("currentMonth", YearMonth.now(clock));
        model.addAttribute("days", from.datesUntil(to.plusDays(1)).toList());
        model.addAttribute("totalColumns", totalColumns);
        model.addAttribute(
                "rows",
                view.rows().stream().map(row -> toRowView(row, from, to, view.weekendDays())).toList());
        model.addAttribute(
                "holidayColumns", view.holidays().stream().map(h -> toHolidayColumnView(h, from)).toList());
        model.addAttribute("weekendDays", view.weekendDays());
        model.addAttribute("departmentOptions", org.listActiveDepartments());
        model.addAttribute("divisionOptions", org.listActiveDivisions());
        model.addAttribute("leaveTypeOptions", leaveTypes.listAll());
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("selectedDivisionId", divisionId);
        model.addAttribute("selectedLeaveTypeId", leaveTypeId);
        return "calendar/index";
    }

    @GetMapping("/settings/calendar")
    @PreAuthorize("isAuthenticated()")
    String settingsPage(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        String token = tokenService.getOrCreateToken(principal.userId());
        model.addAttribute("icalUrl", icalUrl(token));
        return "settings/calendar";
    }

    @PostMapping("/settings/calendar/regenerate")
    @PreAuthorize("isAuthenticated()")
    String regenerate(
            @AuthenticationPrincipal AppUserPrincipal principal, Model model, HttpServletResponse response) {
        String token = tokenService.regenerateToken(principal.userId());
        model.addAttribute("icalUrl", icalUrl(token));
        toast(response, "toast.calendar.regenerated", "Calendar link regenerated");
        return "settings/calendar :: content";
    }

    private static String icalUrl(String token) {
        return RequestTenant.baseUrl() + "/api/v1/calendar/ical.ics?token=" + token;
    }

    private static YearMonth parseMonth(@Nullable String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            return YearMonth.now();
        }
    }

    private CalendarRowView toRowView(
            EmployeeCalendarRow row, LocalDate from, LocalDate to, Set<DayOfWeek> weekendDays) {
        List<BarView> bars =
                row.bars().stream().flatMap(bar -> toBarViews(bar, from, to, weekendDays).stream()).toList();
        return new CalendarRowView(row.employeeId(), row.fullName(), row.departmentName(), bars);
    }

    /**
     * One {@link LeaveBar} becomes one {@link BarView} per contiguous run of working days within
     * its (month-clamped) range — a weekend day inside the range gets no bar segment, so the
     * grid's weekend shading underneath stays visible. Every segment reuses the same tooltip,
     * built once from the bar's original unclamped dates/duration, so hovering any fragment still
     * shows the whole leave period.
     */
    private List<BarView> toBarViews(LeaveBar bar, LocalDate from, LocalDate to, Set<DayOfWeek> weekendDays) {
        LocalDate clampedStart = bar.startDate().isBefore(from) ? from : bar.startDate();
        LocalDate clampedEnd = bar.endDate().isAfter(to) ? to : bar.endDate();
        String tooltip = tooltipFor(bar);
        return splitIntoWorkingSegments(clampedStart, clampedEnd, weekendDays).stream()
                .map(
                        segment -> {
                            int startColumn = (int) ChronoUnit.DAYS.between(from, segment.start()) + 1;
                            int columnSpan = (int) ChronoUnit.DAYS.between(segment.start(), segment.end()) + 1;
                            return new BarView(
                                    bar.leaveTypeName(), boldIcon(bar.icon()), startColumn, columnSpan, tooltip);
                        })
                .toList();
    }

    private String tooltipFor(LeaveBar bar) {
        return messages.getMessage(
                "calendar.grid.bar-tooltip",
                new Object[] {bar.leaveTypeName(), bar.startDate(), bar.endDate(), bar.durationDays()},
                "%s: %s to %s (%s days)".formatted(
                        bar.leaveTypeName(), bar.startDate(), bar.endDate(), bar.durationDays()),
                LocaleContextHolder.getLocale());
    }

    /**
     * Walks {@code [start, end]} one day at a time, breaking into contiguous runs of non-weekend
     * days — a run closes the moment a weekend day is hit and reopens on the next working day. An
     * all-weekend range naturally yields an empty list (nothing to render); an empty {@code
     * weekend} set naturally yields one segment spanning the whole range (today's un-split
     * behavior) — no special-casing needed for either.
     */
    static List<Segment> splitIntoWorkingSegments(LocalDate start, LocalDate end, Set<DayOfWeek> weekend) {
        List<Segment> segments = new ArrayList<>();
        LocalDate segmentStart = null;
        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            boolean isWeekend = weekend.contains(cursor.getDayOfWeek());
            if (!isWeekend && segmentStart == null) {
                segmentStart = cursor;
            } else if (isWeekend && segmentStart != null) {
                segments.add(new Segment(segmentStart, cursor.minusDays(1)));
                segmentStart = null;
            }
        }
        if (segmentStart != null) {
            segments.add(new Segment(segmentStart, end));
        }
        return segments;
    }

    record Segment(LocalDate start, LocalDate end) {}

    /**
     * A holiday applies to every employee, so its grid column is computed once and shared across
     * every row's overlay — unlike leave bars, which are per-employee. Rendered with the exact same
     * {@code .calendar-bar} markup/CSS class as leave bars (see {@code calendar/index.html}) so a
     * holiday looks identical to a leave bar, distinguished only by its bold calendar-heart icon.
     */
    private HolidayColumnView toHolidayColumnView(HolidayMarker holiday, LocalDate from) {
        int column = (int) ChronoUnit.DAYS.between(from, holiday.date()) + 1;
        String tooltip =
                messages.getMessage(
                        "calendar.grid.holiday-tooltip",
                        new Object[] {holiday.name(), holiday.date()},
                        "%s (%s)".formatted(holiday.name(), holiday.date()),
                        LocaleContextHolder.getLocale());
        return new HolidayColumnView(holiday.name(), column, tooltip);
    }

    /**
     * Calendar bars use each leave type's solid "-fill" icon variant for better legibility against
     * the bar's light tinted background. Every entry in {@code AdminLeaveController.ICON_OPTIONS}
     * is deliberately curated to have a "-fill" counterpart in the pinned bootstrap-icons 1.13.1
     * webjar (see that constant's Javadoc), so this needs no exception handling — a future icon
     * option without one would need to be reconsidered there, not worked around here.
     */
    private static @Nullable String boldIcon(@Nullable String icon) {
        return icon == null ? null : icon + "-fill";
    }

    /** Resolves {@code key} through {@code messages.properties} (ADR 0013), then fires the toast. */
    private void toast(HttpServletResponse response, String key, String defaultMessage) {
        String message = messages.getMessage(key, null, defaultMessage, LocaleContextHolder.getLocale());
        response.setHeader("HX-Trigger", "{\"organization-toast\": {\"message\": \"" + message + "\"}}");
    }

    record CalendarRowView(
            UUID employeeId, String fullName, @Nullable String departmentName, List<BarView> bars) {}

    record BarView(
            String leaveTypeName,
            @Nullable String icon,
            int startColumn,
            int columnSpan,
            String tooltip) {}

    record HolidayColumnView(String name, int column, String tooltip) {}
}
