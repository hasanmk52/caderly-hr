package com.helyx.helyxhr.web;

import com.helyx.helyxhr.common.NotFoundException;
import com.helyx.helyxhr.identity.AppUserPrincipal;
import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeForms.SelfProfilePatch;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.people.GovernmentIdType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Self-service profile and cross-employee profile viewing (PRD §14.3, §26). Self-service
 * mutations never take an id from the request — they always resolve the caller's own Employee
 * (see {@link #ownEmployee}) — so field-permission and ownership are enforced by construction,
 * not a runtime "is this really you" check (CLAUDE.md's stated preference, and the same principle
 * {@link com.helyx.helyxhr.people.EmployeeForms} applies to the Self/Admin DTO split).
 *
 * <p>Viewing another employee's profile (Admin, or a manager viewing a report) goes through
 * {@link EmployeeService#getProfileForViewer}, which throws {@code AccessDeniedException} for
 * anyone else — the same 403 page every other access-control failure renders.
 *
 * <p>{@link #view} renders the full page; every mutation handler re-populates the same tab
 * model via {@link #populateTabModel} and returns only the {@code tabContent} fragment, since
 * their {@code hx-target} is {@code #tab-content} — returning the full "people/profile" template
 * there would swap an entire HTML document into that div (see {@code people/profile.html}'s
 * {@code th:fragment="tabContent"}).
 */
@Controller
@PreAuthorize("isAuthenticated()")
class ProfileController {

    private final EmployeeService employees;

    ProfileController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    String ownProfile(@AuthenticationPrincipal AppUserPrincipal principal) {
        return "redirect:/profile/" + ownEmployee(principal).requireId();
    }

    @GetMapping("/profile/{id}")
    @PreAuthorize("isAuthenticated()")
    String view(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "personal") String tab,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model) {
        populateTabModel(id, tab, principal, model);
        return "people/profile";
    }

    @PatchMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    String updateSelf(
            @Valid @ModelAttribute("selfPatch") SelfProfilePatch form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        if (!binding.hasErrors()) {
            employees.updateSelfServiceFields(own.requireId(), form);
            toast(response, "Profile updated");
        }
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    @PostMapping("/profile/emergency-contacts")
    @PreAuthorize("isAuthenticated()")
    String addEmergencyContact(
            @Valid @ModelAttribute("emergencyContactForm") EmergencyContactForm form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        if (!binding.hasErrors()) {
            employees.addEmergencyContact(
                    own.requireId(), form.name(), form.relationship(), form.phone(), form.email());
            toast(response, "Emergency contact added");
        }
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    @DeleteMapping("/profile/emergency-contacts/{contactId}")
    @PreAuthorize("isAuthenticated()")
    String removeEmergencyContact(
            @PathVariable UUID contactId,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        employees.removeEmergencyContact(own.requireId(), contactId);
        toast(response, "Emergency contact removed");
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    @PostMapping("/profile/government-ids")
    @PreAuthorize("isAuthenticated()")
    String addGovernmentId(
            @Valid @ModelAttribute("governmentIdForm") GovernmentIdForm form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        if (!binding.hasErrors()) {
            employees.addGovernmentId(
                    own.requireId(), form.idType(), form.idNumber(), form.country(), form.issueDate(), form.expiryDate());
            toast(response, "Government ID added");
        }
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    @DeleteMapping("/profile/government-ids/{governmentIdId}")
    @PreAuthorize("isAuthenticated()")
    String removeGovernmentId(
            @PathVariable UUID governmentIdId,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        employees.removeGovernmentId(own.requireId(), governmentIdId);
        toast(response, "Government ID removed");
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    private void populateTabModel(UUID id, String tab, AppUserPrincipal principal, Model model) {
        Employee employee = employees.getProfileForViewer(id, principal);
        boolean isSelf = employee.userId() != null && employee.userId().equals(principal.userId());
        model.addAttribute("employee", employee);
        model.addAttribute("isSelf", isSelf);
        model.addAttribute("tenureYears", tenureYears(employee));
        model.addAttribute("activeTab", tab);
        populateTab(tab, employee, model);
        if (!model.containsAttribute("selfPatch")) {
            model.addAttribute(
                    "selfPatch",
                    new SelfProfilePatch(
                            employee.phone(),
                            employee.addressLine1(),
                            employee.addressLine2(),
                            employee.city(),
                            employee.country(),
                            employee.postalCode()));
        }
    }

    private void populateTab(String tab, Employee employee, Model model) {
        UUID employeeId = employee.requireId();
        switch (tab) {
            case "education" -> model.addAttribute("education", employees.listEducation(employeeId));
            case "job" -> model.addAttribute("benefits", employees.listBenefits(employeeId));
            case "documents" -> {
                // Intentionally no data: FileStorage doesn't exist until Phase 1.7. The template
                // renders a stub empty-state, not a disabled upload control (CLAUDE.md: no
                // half-finished UI).
            }
            default -> {
                model.addAttribute("emergencyContacts", employees.listEmergencyContacts(employeeId));
                model.addAttribute("governmentIds", employees.listGovernmentIds(employeeId));
                model.addAttribute("governmentIdTypeOptions", GovernmentIdType.values());
                model.addAttribute("bankDetail", employees.findBankDetail(employeeId).orElse(null));
                if (!model.containsAttribute("emergencyContactForm")) {
                    model.addAttribute("emergencyContactForm", new EmergencyContactForm("", null, null, null));
                }
                if (!model.containsAttribute("governmentIdForm")) {
                    model.addAttribute(
                            "governmentIdForm", new GovernmentIdForm(GovernmentIdType.PASSPORT, "", null, null, null));
                }
            }
        }
    }

    /** Every self-service mutation resolves the caller's own record this way — never from a path id. */
    private Employee ownEmployee(AppUserPrincipal principal) {
        return employees
                .findByUserId(principal.userId())
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "EMPLOYEE_NOT_FOUND",
                                        "No employee record is linked to this account"));
    }

    private static @Nullable Integer tenureYears(Employee employee) {
        LocalDate hireDate = employee.hireDate();
        return hireDate == null ? null : Period.between(hireDate, LocalDate.now()).getYears();
    }

    private static void toast(HttpServletResponse response, String message) {
        response.setHeader("HX-Trigger", "{\"organization-toast\": {\"message\": \"" + message + "\"}}");
    }

    record EmergencyContactForm(
            @NotBlank @jakarta.validation.constraints.Size(max = 150) String name,
            @Nullable String relationship,
            @Nullable String phone,
            @Nullable String email) {}

    record GovernmentIdForm(
            GovernmentIdType idType,
            @NotBlank String idNumber,
            @Nullable String country,
            @Nullable LocalDate issueDate,
            @Nullable LocalDate expiryDate) {}
}
