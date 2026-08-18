package com.helyx.helyxhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.ValidationException;
import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.notifications.system.EmailOutbox;
import com.helyx.helyxhr.notifications.system.EmailOutboxRepository;
import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeForms;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.support.MutableClock;
import com.helyx.helyxhr.support.MutableClockConfiguration;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;

/**
 * The core of Phase 1.6 (PRD §12.4, BR-2/3/4/6/8/9/11) — book/approve/reject/cancel plus the
 * termination cascade. CLAUDE.md §8: "critical logic, TDD first" applies to the access-control
 * decisions and balance math exercised here; the pure duration/state-machine math itself is
 * covered separately by {@code LeaveDurationCalculatorTest}/{@code LeaveRequestStateMachineTest}.
 *
 * <p>{@code MutableClock} is fixed at 2026-08-02T09:00Z (a Sunday). Tests that need a real
 * bookable day use {@link #nextWeekday()} (the next Monday) rather than {@link #today()} — the
 * two "starting today" cancel-window tests are the deliberate exception: they exercise the BR-9
 * boundary on the literal clock date, where a zero-working-day duration doesn't affect the
 * assertion either way.
 */
@Import(MutableClockConfiguration.class)
class LeaveRequestServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private EmployeeService employeeService;
    @Autowired private LeaveTypeService leaveTypeService;
    @Autowired private LeaveRequestService leaveRequestService;
    @Autowired private LeaveBalanceRepository balances;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private AppUserRepository appUsers;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private MutableClock clock;

    // ---- book ----

    @Test
    void book_happyPath_savesPendingRequestAndQueuesEmail() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));

        LeaveRequest request =
                asTenant(
                        tenantA,
                        () ->
                                leaveRequestService.book(
                                        report.requireId(),
                                        type.requireId(),
                                        nextWeekday(),
                                        nextWeekday(),
                                        false,
                                        false,
                                        "Family trip",
                                        BASE_URL));

        assertThat(request.status()).isEqualTo(LeaveRequestStatus.PENDING);
        assertThat(request.durationDays()).isEqualByComparingTo("1.00");
        List<EmailOutbox> queued = asTenant(tenantA, () -> outbox.findByTenantIdOrderByCreatedAtDesc(tenantA));
        assertThat(queued).anyMatch(email -> email.toEmail().equals(manager.email()));
    }

    @Test
    void book_whenSingleRequestExceedsRemaining_throwsInsufficientBalance() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("2"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.book(
                                                        report.requireId(),
                                                        type.requireId(),
                                                        nextWeekday(),
                                                        nextWeekday().plusDays(2), // Mon-Wed: 3 working days > 2 remaining
                                                        false,
                                                        false,
                                                        null,
                                                        BASE_URL)))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_INSUFFICIENT_BALANCE");
    }

    @Test
    void book_whenPendingPlusRequestedExceedsRemaining_throwsInsufficientBalance() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("3"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));

        // First booking: 2 working days, leaves PENDING, does not touch `used` (BR-3).
        asTenant(
                tenantA,
                () ->
                        leaveRequestService.book(
                                report.requireId(),
                                type.requireId(),
                                nextWeekday(),
                                nextWeekday().plusDays(1),
                                false,
                                false,
                                null,
                                BASE_URL));

        // Second booking: 2 more working days. 2 (pending) + 2 (requested) = 4 > 3 remaining.
        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.book(
                                                        report.requireId(),
                                                        type.requireId(),
                                                        nextWeekday().plusDays(7),
                                                        nextWeekday().plusDays(8),
                                                        false,
                                                        false,
                                                        null,
                                                        BASE_URL)))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_INSUFFICIENT_BALANCE");
    }

    @Test
    void book_whenNoManager_notifiesEveryActiveAdmin() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee adminOne = asTenant(tenantA, () -> createActiveAdmin("Admin", "One"));
        Employee adminTwo = asTenant(tenantA, () -> createActiveAdmin("Admin", "Two"));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", null));

        asTenant(
                tenantA,
                () ->
                        leaveRequestService.book(
                                report.requireId(), type.requireId(), today(), today(), false, false, null, BASE_URL));

        List<EmailOutbox> queued = asTenant(tenantA, () -> outbox.findByTenantIdOrderByCreatedAtDesc(tenantA));
        assertThat(queued).anyMatch(email -> email.toEmail().equals(adminOne.email()));
        assertThat(queued).anyMatch(email -> email.toEmail().equals(adminTwo.email()));
    }

    @Test
    void book_whenNoManagerAndNoActiveAdmin_throwsNoApproverAvailable() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", null));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.book(
                                                        report.requireId(),
                                                        type.requireId(),
                                                        today(),
                                                        today(),
                                                        false,
                                                        false,
                                                        null,
                                                        BASE_URL)))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_NO_APPROVER_AVAILABLE");
    }

    @Test
    void book_crossYearRange_throwsValidationException() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.book(
                                                        report.requireId(),
                                                        type.requireId(),
                                                        LocalDate.of(today().getYear(), 12, 30),
                                                        LocalDate.of(today().getYear() + 1, 1, 2),
                                                        false,
                                                        false,
                                                        null,
                                                        BASE_URL)))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_CROSS_YEAR_NOT_SUPPORTED");
    }

    @Test
    void book_backdatedWithoutFlag_throwsValidationException() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10")); // allowsBackdated=false
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.book(
                                                        report.requireId(),
                                                        type.requireId(),
                                                        today().minusDays(1),
                                                        today().minusDays(1),
                                                        false,
                                                        false,
                                                        null,
                                                        BASE_URL)))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_BACKDATED_NOT_ALLOWED");
    }

    @Test
    void book_beyondTwelveMonths_throwsValidationException() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        LocalDate tooFar = today().plusMonths(12).plusDays(1);

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.book(
                                                        report.requireId(),
                                                        type.requireId(),
                                                        tooFar,
                                                        tooFar,
                                                        false,
                                                        false,
                                                        null,
                                                        BASE_URL)))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_BOOKING_WINDOW_EXCEEDED");
    }

    // ---- approve / reject ----

    @Test
    void approve_debitsBalanceAndEmailsRequester() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        LeaveRequest request = bookOneDay(report, type, nextWeekday());

        asTenant(
                tenantA,
                () ->
                        leaveRequestService.approve(
                                request.requireId(),
                                manager.userId(),
                                manager.requireId(),
                                manager.fullName(),
                                false,
                                null));

        LeaveBalance balance = requireBalance(report.requireId(), type.requireId());
        assertThat(balance.used()).isEqualByComparingTo("1.00");
        List<EmailOutbox> queued = asTenant(tenantA, () -> outbox.findByTenantIdOrderByCreatedAtDesc(tenantA));
        assertThat(queued).anyMatch(email -> email.toEmail().equals(report.email()));
    }

    @Test
    void approve_selfApprovalEvenAsAdmin_throwsAccessDenied() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        // A manager on the record purely so book() has somewhere to route the "requested" email —
        // irrelevant to the self-approval check itself, which fires before any role branch.
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee admin = asTenant(tenantA, () -> createEmployee("Self", "Admin", manager.requireId()));
        LeaveRequest request = bookOneDay(admin, type, nextWeekday());

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.approve(
                                                        request.requireId(),
                                                        admin.userId(),
                                                        admin.requireId(),
                                                        admin.fullName(),
                                                        true,
                                                        null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approve_byNonManagerNonAdmin_throwsAccessDenied() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        Employee stranger = asTenant(tenantA, () -> createEmployee("Stranger", "Person", null));
        LeaveRequest request = bookOneDay(report, type, nextWeekday());

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.approve(
                                                        request.requireId(),
                                                        stranger.userId(),
                                                        stranger.requireId(),
                                                        stranger.fullName(),
                                                        false,
                                                        null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approve_byIndirectManager_succeedsViaTransitiveAuthority() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee topManager = asTenant(tenantA, () -> createEmployee("Top", "Manager", null));
        Employee midManager = asTenant(tenantA, () -> createEmployee("Mid", "Manager", topManager.requireId()));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", midManager.requireId()));
        LeaveRequest request = bookOneDay(report, type, nextWeekday());

        LeaveRequest approved =
                asTenant(
                        tenantA,
                        () ->
                                leaveRequestService.approve(
                                        request.requireId(),
                                        topManager.userId(),
                                        topManager.requireId(),
                                        topManager.fullName(),
                                        false,
                                        null));

        assertThat(approved.status()).isEqualTo(LeaveRequestStatus.APPROVED);
    }

    @Test
    void reject_leavesBalanceUnchanged() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        LeaveRequest request = bookOneDay(report, type, nextWeekday());

        LeaveRequest rejected =
                asTenant(
                        tenantA,
                        () ->
                                leaveRequestService.reject(
                                        request.requireId(),
                                        manager.userId(),
                                        manager.requireId(),
                                        manager.fullName(),
                                        false,
                                        "Team is short-staffed that week"));

        assertThat(rejected.status()).isEqualTo(LeaveRequestStatus.REJECTED);
        assertThat(requireBalance(report.requireId(), type.requireId()).used()).isEqualByComparingTo("0.00");
    }

    // ---- cancel ----

    @Test
    void cancel_pendingRequest_cancellableAnytimeBySelf() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        LeaveRequest request = bookOneDay(report, type, nextWeekday());

        LeaveRequest cancelled =
                asTenant(
                        tenantA,
                        () -> leaveRequestService.cancel(request.requireId(), report.userId(), report.requireId(), false));

        assertThat(cancelled.status()).isEqualTo(LeaveRequestStatus.CANCELLED);
    }

    @Test
    void cancel_approvedStartingNextWeekBySelf_creditsBackBalance() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        LeaveRequest request = bookOneDay(report, type, nextWeekday().plusWeeks(1));
        approveAsManager(request, manager);

        asTenant(
                tenantA,
                () -> leaveRequestService.cancel(request.requireId(), report.userId(), report.requireId(), false));

        assertThat(requireBalance(report.requireId(), type.requireId()).used()).isEqualByComparingTo("0.00");
    }

    @Test
    void cancel_approvedStartingTodayBySelf_throwsCancelWindowPassed() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        LeaveRequest request = bookOneDay(report, type, today());
        approveAsManager(request, manager);

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.cancel(
                                                        request.requireId(), report.userId(), report.requireId(), false)))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_CANCEL_WINDOW_PASSED");
    }

    @Test
    void cancel_approvedStartingTodayByAdmin_succeeds() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        LeaveRequest request = bookOneDay(report, type, today());
        approveAsManager(request, manager);

        LeaveRequest cancelled =
                asTenant(
                        tenantA,
                        () -> leaveRequestService.cancel(request.requireId(), report.userId(), report.requireId(), true));

        assertThat(cancelled.status()).isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThat(requireBalance(report.requireId(), type.requireId()).used()).isEqualByComparingTo("0.00");
    }

    @Test
    void cancel_alreadyCancelledRequest_throwsConflictException() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        LeaveRequest request = bookOneDay(report, type, nextWeekday());
        asTenant(
                tenantA,
                () -> leaveRequestService.cancel(request.requireId(), report.userId(), report.requireId(), false));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveRequestService.cancel(
                                                        request.requireId(), report.userId(), report.requireId(), false)))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).errorCode())
                .isEqualTo("LEAVE_REQUEST_ILLEGAL_TRANSITION");
    }

    // ---- termination cascade ----

    @Test
    void cancelFutureRequestsForTermination_cancelsPendingAndApproved_creditsBackOnlyApproved() {
        LeaveType type = asTenant(tenantA, () -> createLeaveType("10"));
        Employee manager = asTenant(tenantA, () -> createEmployee("Mgr", "One", null));
        Employee report = asTenant(tenantA, () -> createEmployee("Rep", "One", manager.requireId()));
        LeaveRequest pending = bookOneDay(report, type, nextWeekday().plusWeeks(1));
        LeaveRequest approved = bookOneDay(report, type, nextWeekday().plusWeeks(2));
        approveAsManager(approved, manager);
        assertThat(requireBalance(report.requireId(), type.requireId()).used()).isEqualByComparingTo("1.00");

        asTenant(
                tenantA,
                () -> {
                    leaveRequestService.cancelFutureRequestsForTermination(report.requireId(), today());
                    return null;
                });

        LeaveRequest freshPending = asTenant(tenantA, () -> leaveRequestRepository.findById(pending.requireId()).orElseThrow());
        LeaveRequest freshApproved = asTenant(tenantA, () -> leaveRequestRepository.findById(approved.requireId()).orElseThrow());
        assertThat(freshPending.status()).isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThat(freshApproved.status()).isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThat(requireBalance(report.requireId(), type.requireId()).used()).isEqualByComparingTo("0.00");
    }

    // ---- helpers ----

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The next Monday strictly after {@link #today()} — a guaranteed non-weekend booking day. */
    private LocalDate nextWeekday() {
        return today().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    private LeaveType createLeaveType(String allowance) {
        return leaveTypeService.create(
                "Annual", null, null, true, true, false, true, new BigDecimal(allowance), null);
    }

    private Employee createEmployee(String firstName, String lastName, UUID managerId) {
        Employee employee =
                employeeService.create(
                        new EmployeeForms.CreateEmployee(
                                firstName,
                                lastName,
                                UUID.randomUUID() + "@example.test",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                LocalDate.of(2020, 1, 1), // historical hire -> full grant for current test year
                                null,
                                null,
                                managerId,
                                null,
                                null,
                                null,
                                null,
                                null),
                        BASE_URL,
                        TENANT_NAME);
        return employeeService.require(employee.requireId());
    }

    private Employee createActiveAdmin(String firstName, String lastName) {
        Employee employee = createEmployee(firstName, lastName, null);
        AppUser user = appUsers.findById(employee.userId()).orElseThrow();
        user.grant(Role.ADMIN);
        user.acceptInvite("test-hash"); // activates without a real invite-redemption flow
        appUsers.save(user);
        return employee;
    }

    private LeaveRequest bookOneDay(Employee requester, LeaveType type, LocalDate day) {
        return asTenant(
                tenantA,
                () ->
                        leaveRequestService.book(
                                requester.requireId(), type.requireId(), day, day, false, false, null, BASE_URL));
    }

    private void approveAsManager(LeaveRequest request, Employee manager) {
        asTenant(
                tenantA,
                () ->
                        leaveRequestService.approve(
                                request.requireId(),
                                manager.userId(),
                                manager.requireId(),
                                manager.fullName(),
                                false,
                                null));
    }

    private LeaveBalance requireBalance(UUID employeeId, UUID leaveTypeId) {
        return asTenant(
                tenantA,
                () ->
                        balances
                                .findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, today().getYear())
                                .orElseThrow());
    }
}
