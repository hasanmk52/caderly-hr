package com.caderly.caderlyhr.api;

import com.caderly.caderlyhr.calendar.CalendarFeedService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one JSON-API-shaped surface in the app so far (PRD §23.2, §6.6 FR-6.5): every other page is
 * a server-rendered {@code web} htmx controller (see {@code CalendarController}'s Javadoc), but
 * this endpoint is consumed by an external, non-browser client — Google Calendar / Outlook — and
 * has to live at this literal, stable URL returning {@code text/calendar}, not an htmx fragment.
 *
 * <p>{@code @PreAuthorize("permitAll()")} matches {@code AuthController}'s exact pattern for a
 * public endpoint — token-authenticated instead of session-authenticated, the one deliberate
 * exception CLAUDE.md §6 A01 anticipates. This alone does not make the endpoint reachable:
 * {@code SecurityConfig}'s {@code securityFilterChain} also lists this exact path in its
 * {@code permitAll()} request matchers, since the filter chain's URL-level authorization runs
 * before method security ever sees this annotation.
 */
@RestController
class CalendarFeedController {

    private final CalendarFeedService feedService;

    CalendarFeedController(CalendarFeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/api/v1/calendar/ical.ics")
    @PreAuthorize("permitAll()")
    ResponseEntity<String> icalFeed(@RequestParam String token) {
        String ics = feedService.buildIcsFeed(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"caderly.ics\"")
                .body(ics);
    }
}
