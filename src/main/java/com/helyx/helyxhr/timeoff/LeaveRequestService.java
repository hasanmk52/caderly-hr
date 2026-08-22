package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.NotFoundException;
import com.helyx.helyxhr.common.ValidationException;
import com.helyx.helyxhr.notifications.EmailOutboxService;
import com.helyx.helyxhr.people.PeopleFacade;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantFacade;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking, approval, and cancellation of leave requests (PRD §12.4, §21) — the core of Phase 1.6.
 * Every controller-facing mutation is a single combined write-then-read {@code @Transactional}
 * method (ADR 0007/0009), wrapping a plain write method the integration tests call directly.
 */
@Service
public class LeaveRequestService {

    private static final Logger log = LoggerFactory.getLogger(LeaveRequestService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final List<LeaveRequestStatus> CASCADE_STATUSES =
            List.of(LeaveRequestStatus.PENDING, LeaveRequestStatus.APPROVED);

    private final LeaveRequestRepository leaveRequests;
    private final LeaveTypeRepository leaveTypes;
    private final LeaveBalanceRepository balances;
    private final PublicHolidayRepository holidays;
    private final BalanceService balanceService;
    private final TenantFacade tenants;
    private final PeopleFacade people;
    private final EmailOutboxService emailOutbox;
    private final Clock clock;

    LeaveRequestService(
            LeaveRequestRepository leaveRequests,
            LeaveTypeRepository leaveTypes,
            LeaveBalanceRepository balances,
            PublicHolidayRepository holidays,
            BalanceService balanceService,
            TenantFacade tenants,
            PeopleFacade people,
            EmailOutboxService emailOutbox,
            Clock clock) {
        this.leaveRequests = leaveRequests;
        this.leaveTypes = leaveTypes;
        this.balances = balances;
        this.holidays = holidays;
        this.balanceService = balanceService;
        this.tenants = tenants;
        this.people = people;
        this.emailOutbox = emailOutbox;
        this.clock = clock;
    }

    // ---- Writes ----

    /**
     * Books a new request (PRD §12.4 steps 1-3). {@code appBaseUrl} is the request's origin
     * (resolved by the web layer, {@code web.RequestTenant.baseUrl()}) — only that layer knows
     * scheme and host, same convention as {@code identity.InviteService}.
     */
    @Transactional
    public LeaveRequest book(
            UUID employeeId,
            UUID leaveTypeId,
            LocalDate start,
            LocalDate end,
            boolean startHalfPm,
            boolean endHalfAm,
            @Nullable String note,
            String appBaseUrl) {
        if (start.getYear() != end.getYear()) {
            throw new ValidationException(
                    "LEAVE_CROSS_YEAR_NOT_SUPPORTED",
                    "A single request cannot span two calendar years");
        }
        LeaveType type =
                leaveTypes
                        .findById(leaveTypeId)
                        .orElseThrow(
                                () -> new NotFoundException("LEAVE_TYPE_NOT_FOUND", "Leave type not found"));

        LocalDate today = LocalDate.now(clock);
        if (start.isBefore(today) && !type.allowsBackdated()) {
            throw new ValidationException(
                    "LEAVE_BACKDATED_NOT_ALLOWED", "This leave type does not allow backdated requests");
        }
        if (start.isAfter(today.plusMonths(12))) {
            throw new ValidationException(
                    "LEAVE_BOOKING_WINDOW_EXCEEDED", "Leave can only be booked up to 12 months ahead");
        }

        BigDecimal duration =
                LeaveDurationCalculator.compute(
                        start, end, startHalfPm, endHalfAm, currentWeekend(), currentHolidays());

        int year = start.getYear();
        // Lock the balance row so a second concurrent booking for the same employee/leave type/
        // year blocks until this transaction commits, instead of both reading the same pending
        // sum and jointly overdrawing the balance.
        LeaveBalance balance = requireBalanceForUpdate(employeeId, leaveTypeId, year);
        BigDecimal pending = leaveRequests.sumPendingDuration(employeeId, leaveTypeId, yearStart(year), yearEnd(year));
        if (pending.add(duration).compareTo(balance.remaining()) > 0) {
            throw new ValidationException(
                    "LEAVE_INSUFFICIENT_BALANCE", "Not enough remaining balance for this request");
        }

        LeaveRequest request =
                leaveRequests.save(
                        LeaveRequest.submit(
                                employeeId, type, start, end, startHalfPm, endHalfAm, duration, note, Instant.now(clock)));

        notifyApprovers(request, appBaseUrl);
        log.info("Booked leave request {} for employee {}", request.requireId(), employeeId);
        return request;
    }

    @Transactional
    public LeaveRequest approve(
            UUID requestId,
            UUID deciderUserId,
            UUID deciderEmployeeId,
            String approverName,
            boolean deciderIsAdmin,
            @Nullable String decisionNote) {
        LeaveRequest request = requireRequest(requestId);
        requireApprovalAuthority(request, deciderEmployeeId, deciderIsAdmin);
        request.approve(deciderUserId, decisionNote, Instant.now(clock));
        balanceService.bookUsed(
                request.employeeId(), request.leaveType().requireId(), request.startDate().getYear(), request.durationDays());
        notifyDecision(request, approverName, true, decisionNote);
        log.info("Approved leave request {}", requestId);
        return request;
    }

    @Transactional
    public LeaveRequest reject(
            UUID requestId,
            UUID deciderUserId,
            UUID deciderEmployeeId,
            String approverName,
            boolean deciderIsAdmin,
            @Nullable String decisionNote) {
        LeaveRequest request = requireRequest(requestId);
        requireApprovalAuthority(request, deciderEmployeeId, deciderIsAdmin);
        request.reject(deciderUserId, decisionNote, Instant.now(clock));
        notifyDecision(request, approverName, false, decisionNote);
        log.info("Rejected leave request {}", requestId);
        return request;
    }

    @Transactional
    public LeaveRequest cancel(UUID requestId, UUID actingUserId, UUID actingEmployeeId, boolean actingIsAdmin) {
        LeaveRequest request = requireRequest(requestId);
        boolean isSelf = request.employeeId().equals(actingEmployeeId);
        if (!isSelf && !actingIsAdmin) {
            throw new AccessDeniedException("Not authorized to cancel this leave request");
        }
        LocalDate today = LocalDate.now(clock);
        // BR-9: an APPROVED request is self-cancellable only up to the day before it starts;
        // cancelling one that starts today or has already started requires an Admin override.
        if (!actingIsAdmin
                && request.status() == LeaveRequestStatus.APPROVED
                && !request.startDate().isAfter(today)) {
            throw new ValidationException(
                    "LEAVE_CANCEL_WINDOW_PASSED",
                    "This request has already started or starts today; ask an Admin to cancel it");
        }

        boolean wasApproved = request.status() == LeaveRequestStatus.APPROVED;
        request.cancel(actingUserId, Instant.now(clock));
        if (wasApproved) {
            balanceService.releaseUsed(
                    request.employeeId(), request.leaveType().requireId(), request.startDate().getYear(), request.durationDays());
        }
        if (isSelf) {
            notifyCancellation(request);
        }
        log.info("Cancelled leave request {} (actor self={})", requestId, isSelf);
        return request;
    }

    /**
     * PRD §12.4 step 7 / §14.4: cancels an employee's still-actionable future requests on
     * termination and credits back any that were already approved. No email (a system action, not
     * a decision) — {@link EmployeeTerminatedEventListener} is the only caller.
     */
    @Transactional
    public void cancelFutureRequestsForTermination(UUID employeeId, LocalDate effectiveDate) {
        List<LeaveRequest> affected =
                leaveRequests.findAllByEmployeeIdAndStatusInAndStartDateGreaterThanEqual(
                        employeeId, CASCADE_STATUSES, effectiveDate);
        Instant now = Instant.now(clock);
        for (LeaveRequest request : affected) {
            boolean wasApproved = request.status() == LeaveRequestStatus.APPROVED;
            request.systemCancel(now);
            if (wasApproved) {
                balanceService.releaseUsed(
                        employeeId, request.leaveType().requireId(), request.startDate().getYear(), request.durationDays());
            }
        }
        if (!affected.isEmpty()) {
            log.info("Cancelled {} future leave request(s) for terminated employee {}", affected.size(), employeeId);
        }
    }

    // ---- Combined write-then-read (ADR 0007/0009) ----

    @Transactional
    public List<BookableType> bookAndListTypes(
            UUID employeeId,
            UUID leaveTypeId,
            LocalDate start,
            LocalDate end,
            boolean startHalfPm,
            boolean endHalfAm,
            @Nullable String note,
            String appBaseUrl) {
        book(employeeId, leaveTypeId, start, end, startHalfPm, endHalfAm, note, appBaseUrl);
        return bookableTypesForEmployee(employeeId);
    }

    @Transactional
    public List<LeaveRequest> approveAndListPending(
            UUID requestId,
            UUID deciderUserId,
            UUID deciderEmployeeId,
            String approverName,
            boolean deciderIsAdmin,
            @Nullable String decisionNote) {
        approve(requestId, deciderUserId, deciderEmployeeId, approverName, deciderIsAdmin, decisionNote);
        return listPendingForApprover(deciderIsAdmin, deciderEmployeeId);
    }

    @Transactional
    public List<LeaveRequest> rejectAndListPending(
            UUID requestId,
            UUID deciderUserId,
            UUID deciderEmployeeId,
            String approverName,
            boolean deciderIsAdmin,
            @Nullable String decisionNote) {
        reject(requestId, deciderUserId, deciderEmployeeId, approverName, deciderIsAdmin, decisionNote);
        return listPendingForApprover(deciderIsAdmin, deciderEmployeeId);
    }

    @Transactional
    public List<LeaveRequest> cancelAndListForEmployee(
            UUID requestId, UUID actingUserId, UUID actingEmployeeId, boolean actingIsAdmin) {
        LeaveRequest cancelled = cancel(requestId, actingUserId, actingEmployeeId, actingIsAdmin);
        return listForEmployee(cancelled.employeeId());
    }

    // ---- Reads ----

    @Transactional(readOnly = true)
    public List<BookableType> bookableTypesForEmployee(UUID employeeId) {
        int year = LocalDate.now(clock).getYear();
        return balances.findAllByEmployeeIdAndYear(employeeId, year).stream()
                .filter(b -> b.leaveType().active())
                .map(
                        b -> {
                            BigDecimal pending =
                                    leaveRequests.sumPendingDuration(
                                            employeeId, b.leaveType().requireId(), yearStart(year), yearEnd(year));
                            return new BookableType(
                                    b.leaveType().requireId(),
                                    b.leaveType().name(),
                                    b.leaveType().icon(),
                                    b.leaveType().color(),
                                    b.leaveType().allowsHalfDay(),
                                    b.leaveType().allowsBackdated(),
                                    b.granted(),
                                    b.used(),
                                    b.remaining(),
                                    b.remaining().subtract(pending));
                        })
                .toList();
    }

    /** Feeds the book modal's live duration/balance indicator. Throws the same way {@link #book} would. */
    @Transactional(readOnly = true)
    public PreviewResult preview(
            UUID employeeId,
            UUID leaveTypeId,
            LocalDate start,
            LocalDate end,
            boolean startHalfPm,
            boolean endHalfAm) {
        if (start.getYear() != end.getYear()) {
            throw new ValidationException(
                    "LEAVE_CROSS_YEAR_NOT_SUPPORTED", "A single request cannot span two calendar years");
        }
        LeaveType type =
                leaveTypes
                        .findById(leaveTypeId)
                        .orElseThrow(
                                () -> new NotFoundException("LEAVE_TYPE_NOT_FOUND", "Leave type not found"));
        BigDecimal duration =
                LeaveDurationCalculator.compute(
                        start, end, startHalfPm, endHalfAm, currentWeekend(), currentHolidays());
        int year = start.getYear();
        LeaveBalance balance = requireBalance(employeeId, leaveTypeId, year);
        BigDecimal pending = leaveRequests.sumPendingDuration(employeeId, leaveTypeId, yearStart(year), yearEnd(year));
        BigDecimal remainingAfter = balance.remaining().subtract(pending).subtract(duration);
        boolean sufficient = remainingAfter.compareTo(BigDecimal.ZERO) >= 0;
        return new PreviewResult(type.name(), duration, remainingAfter, sufficient);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> listForEmployee(UUID employeeId) {
        return leaveRequests.findAllByEmployeeIdOrderBySubmittedAtDesc(employeeId);
    }

    /** Admin sees every PENDING request; a Manager sees only their direct/indirect reports' (PRD §26, transitive). */
    @Transactional(readOnly = true)
    public List<LeaveRequest> listPendingForApprover(boolean isAdmin, UUID approverEmployeeId) {
        List<LeaveRequest> pending = leaveRequests.findAllByStatusOrderBySubmittedAtAsc(LeaveRequestStatus.PENDING);
        if (isAdmin) {
            return pending;
        }
        return pending.stream()
                .filter(r -> people.isManagerOf(approverEmployeeId, r.employeeId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> listCompletedByApprover(UUID deciderUserId) {
        return leaveRequests.findAllByDeciderIdOrderByDecidedAtDesc(deciderUserId);
    }

    // ---- Internal helpers ----

    private LeaveRequest requireRequest(UUID requestId) {
        return leaveRequests
                .findById(requestId)
                .orElseThrow(() -> new NotFoundException("LEAVE_REQUEST_NOT_FOUND", "Leave request not found"));
    }

    private LeaveBalance requireBalance(UUID employeeId, UUID leaveTypeId, int year) {
        return balances
                .findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "LEAVE_BALANCE_NOT_FOUND",
                                        "No " + year + " balance found for this employee/leave type"));
    }

    /** Locking variant of {@link #requireBalance}, used only by {@link #book}. */
    private LeaveBalance requireBalanceForUpdate(UUID employeeId, UUID leaveTypeId, int year) {
        return balances
                .findForUpdateByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "LEAVE_BALANCE_NOT_FOUND",
                                        "No " + year + " balance found for this employee/leave type"));
    }

    /**
     * BR-6, checked unconditionally before any role branch — this is what makes self-approval
     * impossible by construction rather than by convention (an Admin deciding their own request
     * is blocked exactly the same way a stranger would be).
     */
    private void requireApprovalAuthority(LeaveRequest request, UUID deciderEmployeeId, boolean isAdmin) {
        if (request.employeeId().equals(deciderEmployeeId)) {
            throw new AccessDeniedException("Cannot approve or reject your own leave request");
        }
        if (isAdmin) {
            return;
        }
        if (!people.isManagerOf(deciderEmployeeId, request.employeeId())) {
            throw new AccessDeniedException("Not authorized to decide this leave request");
        }
    }

    private void notifyApprovers(LeaveRequest request, String appBaseUrl) {
        PeopleFacade.EmployeeApprovalInfo requester = people.requireEmployeeApprovalInfo(request.employeeId());
        List<PeopleFacade.EmployeeApprovalInfo> approvers =
                requester.managerId() != null
                        ? List.of(people.requireEmployeeApprovalInfo(requester.managerId()))
                        : people.listActiveAdminApprovalInfo();
        if (approvers.isEmpty()) {
            throw new ValidationException(
                    "LEAVE_NO_APPROVER_AVAILABLE", "No manager or Admin is available to approve this request");
        }
        String reviewUrl = appBaseUrl + "/for-action";
        String subject = TimeoffEmails.requestedSubject(requester.fullName());
        String body =
                TimeoffEmails.requestedBody(
                        requester.fullName(), request.leaveType().name(), dateRange(request), request.durationDays(), reviewUrl);
        UUID tenantId = TenantContext.get().orElse(null);
        for (PeopleFacade.EmployeeApprovalInfo approver : approvers) {
            emailOutbox.enqueue(tenantId, approver.email(), subject, body);
        }
    }

    private void notifyDecision(LeaveRequest request, String approverName, boolean approved, @Nullable String decisionNote) {
        PeopleFacade.EmployeeApprovalInfo requester = people.requireEmployeeApprovalInfo(request.employeeId());
        UUID tenantId = TenantContext.get().orElse(null);
        String leaveTypeName = request.leaveType().name();
        String dateRange = dateRange(request);
        String subject =
                approved ? TimeoffEmails.approvedSubject(leaveTypeName) : TimeoffEmails.rejectedSubject(leaveTypeName);
        String body =
                approved
                        ? TimeoffEmails.approvedBody(leaveTypeName, dateRange, approverName)
                        : TimeoffEmails.rejectedBody(
                                leaveTypeName, dateRange, approverName, decisionNote == null ? "" : decisionNote);
        emailOutbox.enqueue(tenantId, requester.email(), subject, body);
    }

    /**
     * Notifies "the approver" the employee cancelling their own request (decision 7 in the phase
     * plan). Only the first active Admin is emailed in the no-manager case — unlike the
     * "requested" event, which emails every Admin — because a cancellation notice is lower-stakes
     * and PRD §12.4 does not specify plural here.
     */
    private void notifyCancellation(LeaveRequest request) {
        PeopleFacade.EmployeeApprovalInfo requester = people.requireEmployeeApprovalInfo(request.employeeId());
        PeopleFacade.EmployeeApprovalInfo approver;
        if (requester.managerId() != null) {
            approver = people.requireEmployeeApprovalInfo(requester.managerId());
        } else {
            List<PeopleFacade.EmployeeApprovalInfo> admins = people.listActiveAdminApprovalInfo();
            if (admins.isEmpty()) {
                return;
            }
            approver = admins.getFirst();
        }
        UUID tenantId = TenantContext.get().orElse(null);
        String subject = TimeoffEmails.cancelledSubject(requester.fullName(), request.leaveType().name());
        String body = TimeoffEmails.cancelledBody(requester.fullName(), request.leaveType().name(), dateRange(request));
        emailOutbox.enqueue(tenantId, approver.email(), subject, body);
    }

    private Set<java.time.DayOfWeek> currentWeekend() {
        return LeaveDurationCalculator.decodeWeekend(tenants.currentWeekendDays());
    }

    private Set<LocalDate> currentHolidays() {
        return holidays.findAllByOrderByDateAsc().stream().map(PublicHoliday::date).collect(Collectors.toSet());
    }

    private static LocalDate yearStart(int year) {
        return LocalDate.of(year, 1, 1);
    }

    private static LocalDate yearEnd(int year) {
        return LocalDate.of(year, 12, 31);
    }

    private static String dateRange(LeaveRequest request) {
        if (request.startDate().equals(request.endDate())) {
            return request.startDate().format(DATE_FMT);
        }
        return request.startDate().format(DATE_FMT) + " - " + request.endDate().format(DATE_FMT);
    }

    /** Feeds the Book modal's chip row (UI_Guidelines §8.1). */
    public record BookableType(
            UUID leaveTypeId,
            String name,
            @Nullable String icon,
            @Nullable String color,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            BigDecimal granted,
            BigDecimal used,
            BigDecimal remaining,
            BigDecimal remainingAfterPending) {}

    /** Feeds the Book modal's live duration/balance preview. */
    public record PreviewResult(
            String leaveTypeName, BigDecimal duration, BigDecimal remainingAfter, boolean sufficient) {}
}
