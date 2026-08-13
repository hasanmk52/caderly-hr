package com.helyx.helyxhr.web;

import com.helyx.helyxhr.identity.AppUserPrincipal;
import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeService;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HomeController {

    private final EmployeeService employees;

    HomeController(EmployeeService employees) {
        this.employees = employees;
    }

    /**
     * Any signed-in user of this tenant; the role hierarchy makes EMPLOYEE the floor.
     *
     * <p>{@code firstName} is null for a principal with no linked {@link Employee} — an Admin-only
     * account such as {@code DevDataSeeder}'s dev bootstrap, or a test's mock {@code
     * UserDetails} that isn't really an {@link AppUserPrincipal} at all — so the greeting (PRD
     * §24.2) falls back to a name-free "Welcome!" instead of failing.
     */
    @GetMapping("/")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String home(@Nullable @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        String firstName =
                principal == null
                        ? null
                        : employees.findByUserId(principal.userId()).map(Employee::firstName).orElse(null);
        model.addAttribute("firstName", firstName);
        return "home";
    }
}
