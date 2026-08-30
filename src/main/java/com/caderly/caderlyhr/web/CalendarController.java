package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.calendar.CalendarService;
import com.caderly.caderlyhr.calendar.CalendarService.EmployeeCalendarRow;
import com.caderly.caderlyhr.calendar.CalendarService.LeaveBar;
import com.caderly.caderlyhr.calendar.CalendarService.TeamCalendarView;
import com.caderly.caderlyhr.calendar.CalendarTokenService;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.org.OrgFacade;
import com.caderly.caderlyhr.timeoff.LeaveTypeService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
        model.addAttribute("rows", view.rows().stream().map(row -> toRowView(row, from, to)).toList());
        model.addAttribute("holidayDates", view.holidays().stream().map(h -> h.date()).toList());
        model.addAttribute("holidays", view.holidays());
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

    private CalendarRowView toRowView(EmployeeCalendarRow row, LocalDate from, LocalDate to) {
        List<BarView> bars = row.bars().stream().map(bar -> toBarView(bar, from, to)).toList();
        return new CalendarRowView(row.employeeId(), row.fullName(), row.departmentName(), bars);
    }

    private BarView toBarView(LeaveBar bar, LocalDate from, LocalDate to) {
        LocalDate clampedStart = bar.startDate().isBefore(from) ? from : bar.startDate();
        LocalDate clampedEnd = bar.endDate().isAfter(to) ? to : bar.endDate();
        int startColumn = (int) ChronoUnit.DAYS.between(from, clampedStart) + 1;
        int columnSpan = (int) ChronoUnit.DAYS.between(clampedStart, clampedEnd) + 1;
        String tooltip =
                messages.getMessage(
                        "calendar.grid.bar-tooltip",
                        new Object[] {bar.leaveTypeName(), bar.startDate(), bar.endDate(), bar.durationDays()},
                        "%s: %s to %s (%s days)".formatted(
                                bar.leaveTypeName(), bar.startDate(), bar.endDate(), bar.durationDays()),
                        LocaleContextHolder.getLocale());
        return new BarView(bar.leaveTypeName(), bar.color(), bar.icon(), startColumn, columnSpan, tooltip);
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
            @Nullable String color,
            @Nullable String icon,
            int startColumn,
            int columnSpan,
            String tooltip) {}
}
