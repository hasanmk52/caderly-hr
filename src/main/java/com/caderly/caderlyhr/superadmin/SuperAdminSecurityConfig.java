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

/**
 * The Super Admin security realm (PRD FR-1.8): a second {@link SecurityFilterChain} over {@code
 * /superadmin/**}, authenticating against {@code super_admin} rather than {@code app_user}.
 *
 * <p>{@code @Order(1)} puts it ahead of {@code security.SecurityConfig}'s {@code @Order(100)}
 * chain, which carries no {@code securityMatcher} and would otherwise match these URLs first and
 * bounce an operator to the tenant login page.
 *
 * <p>Two realms in one application only stay separate if their authentication does. See {@link
 * #superAdminAuthenticationManager} for what that costs and why it is not a shared bean.
 */
@Configuration(proxyBeanMethods = false)
class SuperAdminSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain superAdminSecurityFilterChain(
            HttpSecurity http, SuperAdminDetailsService superAdmins, PasswordEncoder passwordEncoder)
            throws Exception {
        http.securityMatcher("/superadmin/**")
                .authenticationManager(superAdminAuthenticationManager(superAdmins, passwordEncoder))
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
