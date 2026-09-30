package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.security.SecurityPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Super Admin login page (PRD FR-1.8). {@code
 * superadmin.SuperAdminSecurityConfig#superAdminSecurityFilterChain} names {@link
 * SecurityPaths#SUPER_ADMIN_LOGIN_PATH} as this realm's {@code formLogin().loginPage(...)}, but
 * Spring Security's form login only redirects an unauthenticated request there — it does not
 * itself serve a GET response for a custom login page, unlike the framework-generated default.
 * This controller is that GET, mirroring {@code web.AuthController#loginPage}'s shape exactly.
 *
 * <p>{@code @PreAuthorize("permitAll()")} rather than relying on {@code
 * SuperAdminSecurityConfig}'s own {@code permitAll()} URL rule for the same reason {@code
 * AuthController} states for its own pages: CLAUDE.md §6 A01 wants every controller method to say
 * "public" explicitly, not merely benefit from a URL rule declared elsewhere.
 */
@Controller
class SuperAdminAuthController {

    @GetMapping(SecurityPaths.SUPER_ADMIN_LOGIN_PATH)
    @PreAuthorize("permitAll()")
    String loginPage() {
        return "superadmin/login";
    }
}
