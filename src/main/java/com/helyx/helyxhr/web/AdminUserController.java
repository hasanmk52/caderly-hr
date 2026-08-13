package com.helyx.helyxhr.web;

import com.helyx.helyxhr.identity.InviteService;
import com.helyx.helyxhr.identity.Role;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Admin-only, read-only list of every login account and its roles (PRD §26).
 *
 * <p>Creating a user happens exclusively through People &gt; Add Employee ({@link
 * com.helyx.helyxhr.web.AdminEmployeeController}), which creates the {@link
 * com.helyx.helyxhr.identity.AppUser} and its {@link com.helyx.helyxhr.people.Employee} together
 * in one transaction (PRD §5: an Admin or Manager is also always an Employee). This page used to
 * carry its own separate invite form (sub-phase 1.2, before the Employee record existed); that
 * form was removed because it created accounts with no Employee — inviting there does not put
 * the person on the People page or give them a profile.
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
        return "admin/users";
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
                                        user.status().label(),
                                        user.roles().stream().map(Role::label).sorted().toList(),
                                        user.lastLoginAt() == null ? null : user.lastLoginAt().toString()))
                .toList();
    }

    record UserRow(
            String email, String status, String statusLabel, List<String> roles, String lastLoginAt) {}
}
