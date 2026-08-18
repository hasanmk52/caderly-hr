package com.helyx.helyxhr.web;

import com.helyx.helyxhr.common.NotFoundException;
import com.helyx.helyxhr.identity.AppUserPrincipal;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.timeoff.LeaveRequest;
import com.helyx.helyxhr.timeoff.LeaveRequestService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * "For Action" leave approval inbox (PRD §12.4 steps 3-6, §26; UI_Guidelines §8.6). Manager
 * approval authority is transitive (direct + indirect reports, via {@code
 * PeopleFacade#isManagerOf}) even though routing/notification at submit time stays direct-manager-
 * only (BR-2 MVP scope) — see the Phase 1.6 plan's decision 5. {@code @PreAuthorize
 * hasRole('MANAGER')} is the floor; the role hierarchy passes Admin through too.
 */
@Controller
@PreAuthorize("hasRole('MANAGER')")
class LeaveApprovalController {

    private final LeaveRequestService leaveRequests;
    private final EmployeeService employees;

    LeaveApprovalController(LeaveRequestService leaveRequests, EmployeeService employees) {
        this.leaveRequests = leaveRequests;
        this.employees = employees;
    }

    @GetMapping("/for-action")
    @PreAuthorize("hasRole('MANAGER')")
    String forAction(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Decider decider = resolveDecider(principal);
        model.addAttribute(
                "pending", toRows(leaveRequests.listPendingForApprover(decider.isAdmin(), decider.employeeId())));
        model.addAttribute("completed", toRows(leaveRequests.listCompletedByApprover(principal.userId())));
        model.addAttribute("rejectForm", new LeaveForms.RejectForm(null));
        return "for-action";
    }

    @GetMapping("/for-action/leave-requests/{id}/reject-form")
    @PreAuthorize("hasRole('MANAGER')")
    String rejectForm(@PathVariable UUID id, Model model) {
        model.addAttribute("rejectRequestId", id);
        model.addAttribute("rejectForm", new LeaveForms.RejectForm(null));
        return "for-action :: rejectForm";
    }

    @PostMapping("/for-action/leave-requests/{id}/approve")
    @PreAuthorize("hasRole('MANAGER')")
    String approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Decider decider = resolveDecider(principal);
        List<LeaveRequest> fresh =
                leaveRequests.approveAndListPending(
                        id, principal.userId(), decider.employeeId(), decider.name(), decider.isAdmin(), null);
        toast(response, "Request approved");
        model.addAttribute("pending", toRows(fresh));
        return "for-action :: pendingList";
    }

    @PostMapping("/for-action/leave-requests/{id}/reject")
    @PreAuthorize("hasRole('MANAGER')")
    String reject(
            @PathVariable UUID id,
            @Valid @ModelAttribute("rejectForm") LeaveForms.RejectForm form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Decider decider = resolveDecider(principal);
        if (!binding.hasErrors()) {
            List<LeaveRequest> fresh =
                    leaveRequests.rejectAndListPending(
                            id,
                            principal.userId(),
                            decider.employeeId(),
                            decider.name(),
                            decider.isAdmin(),
                            form.decisionNote());
            toast(response, "Request rejected");
            model.addAttribute("pending", toRows(fresh));
            return "for-action :: pendingList";
        }
        model.addAttribute("rejectRequestId", id);
        return "for-action :: rejectForm";
    }

    private Decider resolveDecider(AppUserPrincipal principal) {
        boolean isAdmin = principal.roles().contains(Role.ADMIN);
        Employee employee =
                employees
                        .findByUserId(principal.userId())
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "EMPLOYEE_NOT_FOUND", "No employee record is linked to this account"));
        return new Decider(employee.requireId(), employee.fullName(), isAdmin);
    }

    /**
     * {@link LeaveRequest#employeeId()} is a plain id, not a JPA relation ({@code timeoff} never
     * reaches into {@code people}'s entities — CLAUDE.md §4), so the employee's display name has
     * to be resolved here in the web layer rather than read off the entity in the template.
     */
    private List<ApprovalRow> toRows(List<LeaveRequest> requests) {
        return requests.stream().map(this::toRow).toList();
    }

    private ApprovalRow toRow(LeaveRequest request) {
        Employee employee = employees.require(request.employeeId());
        return new ApprovalRow(
                request.requireId(),
                employee.fullName(),
                request.leaveType().name(),
                request.leaveType().icon(),
                request.leaveType().color(),
                request.startDate(),
                request.endDate(),
                request.durationDays(),
                request.note(),
                request.submittedAt(),
                request.status().label(),
                request.decisionNote());
    }

    private static void toast(HttpServletResponse response, String message) {
        response.setHeader("HX-Trigger", "{\"organization-toast\": {\"message\": \"" + message + "\"}}");
    }

    private record Decider(UUID employeeId, String name, boolean isAdmin) {}

    /** Feeds {@code for-action.html} (UI_Guidelines §8.6): a {@link LeaveRequest} plus its requester's name. */
    record ApprovalRow(
            UUID requestId,
            String employeeName,
            String leaveTypeName,
            @Nullable String icon,
            @Nullable String color,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal durationDays,
            @Nullable String note,
            Instant submittedAt,
            String statusLabel,
            @Nullable String decisionNote) {}
}
