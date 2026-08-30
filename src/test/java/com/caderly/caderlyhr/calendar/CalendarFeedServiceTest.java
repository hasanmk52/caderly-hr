package com.caderly.caderlyhr.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeRepository;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import com.caderly.caderlyhr.timeoff.LeaveRequest;
import com.caderly.caderlyhr.timeoff.LeaveRequestRepository;
import com.caderly.caderlyhr.timeoff.LeaveType;
import com.caderly.caderlyhr.timeoff.LeaveTypeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The public iCal feed's assembly (PRD AC-CALENDAR.1): valid token -> the token owner's own
 * approved leave as VEVENTs; anything else -> a clean {@link NotFoundException}, never a silent
 * empty-but-200 feed (Phase 1.8 DoD's explicit requirement).
 */
class CalendarFeedServiceTest extends TenantIsolationTestBase {

    @Autowired private CalendarFeedService feedService;
    @Autowired private CalendarTokenService tokenService;
    @Autowired private AppUserRepository users;
    @Autowired private EmployeeRepository employees;
    @Autowired private LeaveTypeRepository leaveTypes;
    @Autowired private LeaveRequestRepository leaveRequests;

    @Test
    void buildIcsFeed_withValidToken_returnsOnlyThatEmployeesApprovedLeave() {
        LeaveType type = asTenant(tenantA, () -> leaveTypes.save(vacation()));
        AppUser user = asTenant(tenantA, () -> users.save(AppUser.active(uniqueEmail(), "hash")));
        Employee employee = asTenant(tenantA, () -> linkedEmployee(user.getId()));
        asTenant(
                tenantA,
                () ->
                        leaveRequests.save(
                                approve(
                                        submit(
                                                employee.requireId(), type, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10)))));
        String token = asTenant(tenantA, () -> tokenService.getOrCreateToken(user.getId()));

        String ics = asTenant(tenantA, () -> feedService.buildIcsFeed(token));

        assertThat(ics).contains("BEGIN:VEVENT");
        assertThat(ics).contains("SUMMARY:Vacation");
    }

    @Test
    void buildIcsFeed_withUnknownToken_throwsNotFound() {
        assertThatThrownBy(() -> asTenant(tenantA, () -> feedService.buildIcsFeed("does-not-exist")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void buildIcsFeed_whenUserHasNoLinkedEmployee_throwsNotFound() {
        AppUser user = asTenant(tenantA, () -> users.save(AppUser.active(uniqueEmail(), "hash")));
        String token = asTenant(tenantA, () -> tokenService.getOrCreateToken(user.getId()));

        assertThatThrownBy(() -> asTenant(tenantA, () -> feedService.buildIcsFeed(token)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void buildIcsFeed_whenTokenBelongsToAnotherTenant_throwsNotFound() {
        AppUser user = asTenant(tenantA, () -> users.save(AppUser.active(uniqueEmail(), "hash")));
        String token = asTenant(tenantA, () -> tokenService.getOrCreateToken(user.getId()));

        assertThatThrownBy(() -> asTenant(tenantB, () -> feedService.buildIcsFeed(token)))
                .isInstanceOf(NotFoundException.class);
    }

    private Employee linkedEmployee(UUID userId) {
        Employee employee = Employee.create("Feed", "Owner", uniqueEmail());
        employee.linkUser(userId);
        return employees.save(employee);
    }

    private static LeaveType vacation() {
        return LeaveType.create("Vacation", null, null, true, false, false, true, BigDecimal.TEN, null);
    }

    private static LeaveRequest submit(UUID employeeId, LeaveType type, LocalDate start, LocalDate end) {
        return LeaveRequest.submit(employeeId, type, start, end, false, false, new BigDecimal("1.00"), null, Instant.now());
    }

    private static LeaveRequest approve(LeaveRequest request) {
        // decider_id has a real FK to app_user(id); null is allowed and simpler than seeding one.
        request.approve(null, null, Instant.now());
        return request;
    }

    private static String uniqueEmail() {
        return "feed-" + UUID.randomUUID() + "@example.test";
    }
}
