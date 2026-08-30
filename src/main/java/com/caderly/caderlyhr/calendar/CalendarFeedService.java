package com.caderly.caderlyhr.calendar;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.people.PeopleFacade;
import com.caderly.caderlyhr.timeoff.TimeoffFacade;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the public iCal feed (PRD §6.6 FR-6.5, AC-CALENDAR.1, {@code GET
 * /api/v1/calendar/ical.ics}) — the token owner's own approved leave only (a scope decision made
 * with the user during planning: FR-6.5's "optionally team's leave" is out of scope for this
 * phase; AC-CALENDAR.1 and the Phase 1.8 DoD both describe only the subscriber's own leave).
 *
 * <p>No {@code TenantContext.runAsSystem} here: {@code TenantResolutionFilter} already resolves
 * the tenant from the request's subdomain before this runs (verified against {@code
 * PasswordResetService}'s identical unauthenticated-but-subdomain-resolved shape), so {@link
 * AppUserRepository#findByIcalToken} is already tenant-scoped by {@code @TenantId}.
 */
@Service
public class CalendarFeedService {

    private final AppUserRepository users;
    private final PeopleFacade people;
    private final TimeoffFacade timeoff;
    private final Clock clock;

    CalendarFeedService(AppUserRepository users, PeopleFacade people, TimeoffFacade timeoff, Clock clock) {
        this.users = users;
        this.people = people;
        this.timeoff = timeoff;
        this.clock = clock;
    }

    /**
     * @throws NotFoundException if the token is missing/unknown, or if it resolves to a login
     *     with no linked Employee (a dev/system account) — both cases are a 404, per the DoD's
     *     "clear 4xx, not a 500 or a silent empty calendar" requirement.
     */
    @Transactional(readOnly = true)
    public String buildIcsFeed(String token) {
        AppUser user =
                users.findByIcalToken(token)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "CALENDAR_TOKEN_INVALID", "This calendar link is invalid"));
        var employeeId =
                people.findEmployeeIdByUserId(user.requireId())
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "CALENDAR_TOKEN_INVALID", "This calendar link is invalid"));
        var entries = timeoff.listAllApprovedLeaveForEmployee(employeeId);
        return IcsFeedWriter.write(entries, clock.instant());
    }
}
