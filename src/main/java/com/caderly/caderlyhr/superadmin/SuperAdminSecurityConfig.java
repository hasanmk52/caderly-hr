package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.security.SecurityHeaders;
import com.caderly.caderlyhr.security.SecurityPaths;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * The Super Admin security realm (PRD FR-1.8): a second {@link SecurityFilterChain} over {@code
 * /superadmin/**}, authenticating against {@code super_admin} rather than {@code app_user}.
 *
 * <p>{@code @Order(1)} puts it ahead of {@code security.SecurityConfig}'s {@code @Order(100)}
 * chain, which carries no {@code securityMatcher} and would otherwise match these URLs first and
 * bounce an operator to the tenant login page.
 *
 * <p>Two realms in one application only stay separate if their authentication does. See {@link
 * #superAdminAuthenticationManager} for what that costs and why it is not a shared bean, and
 * {@link #superAdminSecurityContextRepository} for the other half of the same problem — a shared
 * {@code HttpSession}.
 */
@Configuration(proxyBeanMethods = false)
class SuperAdminSecurityConfig {

    /**
     * Where this realm's {@code SecurityContext} lives in the {@code HttpSession}, deliberately
     * <em>not</em> {@code HttpSessionSecurityContextRepository}'s default {@code
     * SPRING_SECURITY_CONTEXT}. See {@link #superAdminSecurityContextRepository}.
     */
    static final String SECURITY_CONTEXT_KEY = "SUPERADMIN_SECURITY_CONTEXT";

    @Bean
    @Order(1)
    SecurityFilterChain superAdminSecurityFilterChain(
            HttpSecurity http,
            SuperAdminDetailsService superAdmins,
            PasswordEncoder passwordEncoder)
            throws Exception {
        http.securityMatcher("/superadmin/**")
                .authenticationManager(superAdminAuthenticationManager(superAdmins, passwordEncoder))
                .securityContext(
                        context -> context.securityContextRepository(superAdminSecurityContextRepository()))
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(SecurityPaths.SUPER_ADMIN_LOGIN_PATH)
                                        .permitAll()
                                        // Default-deny, and the only role this realm knows. Note the tenant
                                        // role hierarchy (ADMIN → MANAGER → EMPLOYEE) does not reach
                                        // SUPER_ADMIN, so no tenant session can satisfy this.
                                        .anyRequest()
                                        .hasRole("SUPER_ADMIN"))
                .formLogin(
                        form ->
                                form.loginPage(SecurityPaths.SUPER_ADMIN_LOGIN_PATH)
                                        .loginProcessingUrl(SecurityPaths.SUPER_ADMIN_LOGIN_PATH)
                                        .usernameParameter("email")
                                        .defaultSuccessUrl("/superadmin/tenants", true)
                                        // One generic failure destination, for the same reason as the tenant
                                        // login (CLAUDE.md §6 A07).
                                        .failureUrl(SecurityPaths.SUPER_ADMIN_LOGIN_PATH + "?error")
                                        .permitAll())
                .logout(
                        logout ->
                                logout
                                        .logoutUrl("/superadmin/logout")
                                        .logoutSuccessUrl(SecurityPaths.SUPER_ADMIN_LOGIN_PATH + "?loggedOut")
                                        .invalidateHttpSession(true)
                                        .deleteCookies("JSESSIONID")
                                        .permitAll())
                .headers(SecurityHeaders.standard());

        // CSRF stays at the Spring Security default (on), exactly as on the tenant chain.
        return http.build();
    }

    /**
     * A standalone {@link ProviderManager} for this chain only.
     *
     * <p>Not a {@code @Bean}: a single {@code AuthenticationManager} bean in the context becomes
     * the <em>parent</em> manager of every chain, so a rejected Super Admin login would be retried
     * against {@code app_user} and a rejected tenant login against {@code super_admin}. Building it
     * here, unpublished, is what makes cross-realm credential checking impossible rather than
     * merely unintended.
     *
     * <p>It also deliberately publishes no authentication events, unlike the tenant chain's
     * manager. {@code security.LoginAttemptListener} writes a {@code login_audit} row and evaluates
     * the per-tenant lockout on every event it sees, and both are tenant-scoped writes — on a
     * {@code /superadmin/**} thread, where {@code TenantResolutionFilter} never ran, {@code
     * TenantSessionVariableListener} would reject the transaction outright. Super Admin login
     * auditing is its own concern with its own (cross-tenant) shape, not a reuse of this one.
     */
    private static AuthenticationManager superAdminAuthenticationManager(
            SuperAdminDetailsService superAdmins, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(superAdmins);
        provider.setPasswordEncoder(passwordEncoder);
        provider.afterPropertiesSet();
        return new ProviderManager(provider);
    }

    /**
     * This realm's own session slot, under {@link #SECURITY_CONTEXT_KEY}.
     *
     * <p>Separate authentication managers are only half of realm isolation. Both chains run in one
     * application and therefore share one {@code HttpSession}, and the Spring Security default
     * {@code HttpSessionSecurityContextRepository} reads and writes the same {@code
     * SPRING_SECURITY_CONTEXT} attribute for every chain. Left at the default, an operator who
     * logged in here and then opened a tenant subdomain in the same browser would arrive at the
     * tenant chain already authenticated: {@code anyRequest().authenticated()} is satisfied by any
     * non-anonymous principal, so every page gated only by {@code @PreAuthorize("isAuthenticated()")}
     * — {@code web.FilesController}, {@code web.ProfileController}, {@code web.CalendarController} —
     * would serve that tenant's data, with no impersonation record anywhere. Writing this realm's
     * context under a different key means the tenant chain simply finds nothing, and the reverse
     * holds too.
     *
     * <p>Composed exactly as {@code SecurityContextConfigurer}'s default is (session repository
     * first, request-attribute repository second) so only the key changes; dropping the
     * request-attribute half would silently lose the context across a {@code FORWARD}/{@code ERROR}
     * dispatch.
     */
    private static SecurityContextRepository superAdminSecurityContextRepository() {
        HttpSessionSecurityContextRepository sessionRepository = new HttpSessionSecurityContextRepository();
        sessionRepository.setSpringSecurityContextKey(SECURITY_CONTEXT_KEY);
        return new DelegatingSecurityContextRepository(
                sessionRepository, new RequestAttributeSecurityContextRepository());
    }

    /**
     * Registered at {@code HIGHEST_PRECEDENCE + 1} — ahead of {@code RateLimitFilter} ({@code + 10})
     * and far ahead of Spring Security's own chain (order −100), so a request from outside the
     * allowlist is refused before it can consume a rate-limit token or reach the login filter.
     *
     * <p>Its position relative to {@code TenantResolutionFilter} (exactly {@code
     * HIGHEST_PRECEDENCE}, so unavoidably first) does not matter: that filter's {@code
     * shouldNotFilter} skips {@code /superadmin} entirely, making it a pass-through here.
     */
    @Bean
    FilterRegistrationBean<SuperAdminIpAllowlistFilter> superAdminIpAllowlistFilterRegistration(
            @Value("${caderly.superadmin.ip-allowlist:}") String ipAllowlist) {
        FilterRegistrationBean<SuperAdminIpAllowlistFilter> registration =
                new FilterRegistrationBean<>(new SuperAdminIpAllowlistFilter(parseCidrs(ipAllowlist)));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /** Blank or unset yields an empty list, which the filter treats as "deny everyone". */
    private static List<String> parseCidrs(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(cidr -> !cidr.isEmpty())
                .toList();
    }
}
