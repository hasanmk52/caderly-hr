package com.helyx.helyxhr.web;

import com.helyx.helyxhr.common.HelyxException;
import com.helyx.helyxhr.identity.InviteService;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.tenant.TenantSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin-only user list and invite form (PRD §26: only Admin may create users).
 *
 * <p>Minimal by intent. Sub-phase 1.2 needs somewhere for an Admin to actually trigger an invite;
 * full user administration arrives with the employee record in 1.4.
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController {

    private final InviteService invites;

    AdminUserController(InviteService invites) {
        this.invites = invites;
    }

    /**
     * The class-level {@code @PreAuthorize} already covers this, but CLAUDE.md §6 A01 asks for an
     * annotation on every method and the ArchUnit rule checks for one — an inherited grant is
     * easy to lose by moving a method to another class.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    String listUsers(Model model) {
        model.addAttribute("users", userRows());
        model.addAttribute("allRoles", Role.values());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new AuthForms.InviteUser("", Set.of(Role.EMPLOYEE)));
        }
        return "admin/users";
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    String inviteUser(
            @Valid @ModelAttribute("form") AuthForms.InviteUser form,
            BindingResult binding,
            HttpServletRequest request,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (binding.hasErrors()) {
            model.addAttribute("users", userRows());
            model.addAttribute("allRoles", Role.values());
            return "admin/users";
        }

        TenantSummary tenant = RequestTenant.of(request);
        try {
            invites.invite(form.email(), form.roles(), RequestTenant.baseUrl(), tenant.name());
        } catch (HelyxException exception) {
            // Duplicate email is a normal outcome of this form, not an exceptional one — render
            // it on the field instead of letting it reach the global handler's error page.
            binding.rejectValue("email", exception.errorCode(), exception.getMessage());
            model.addAttribute("users", userRows());
            model.addAttribute("allRoles", Role.values());
            return "admin/users";
        }

        redirectAttributes.addFlashAttribute("invited", form.email());
        return "redirect:/admin/users";
    }

    /**
     * Flattens entities into a view record before they leave the transaction: templates never
     * touch entities (CLAUDE.md §7), and the roles association is lazy in the general case.
     */
    private List<UserRow> userRows() {
        return invites.listUsers().stream()
                .map(
                        user ->
                                new UserRow(
                                        user.email(),
                                        user.status().name(),
                                        user.roles().stream().map(Enum::name).sorted().toList(),
                                        user.lastLoginAt() == null ? null : user.lastLoginAt().toString()))
                .toList();
    }

    record UserRow(String email, String status, List<String> roles, String lastLoginAt) {}
}
