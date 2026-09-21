package com.caderly.caderlyhr.security;

import com.caderly.caderlyhr.identity.AppUserDetailsService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Authentication and authorization for the tenant realm (PRD §19.1, §19.2, §19.6; CLAUDE.md §6).
 * Shape agreed in ADR 0006 before implementation, per the CLAUDE.md §12 ask-first rule.
 *
 * <p>Still no <em>custom</em> {@code AuthenticationProvider} (ADR 0006 decision A): stock {@code
 * DaoAuthenticationProvider} over {@code AppUserDetailsService} is already tenant-scoped, because
 * {@code AppUser} carries {@code @TenantId}. What changed in sub-phase 1.13 is that the provider is
 * now <em>constructed here</em> rather than inferred. Spring Security only auto-wires one from a
 * {@code UserDetailsService} bean when the context holds exactly one, and {@code
 * superadmin.SuperAdminDetailsService} is a second — with two present it wires neither, for
 * either realm. Each chain therefore names its own store explicitly, which is also what keeps
 * Super Admin credentials from ever being checked against {@code app_user}, or the reverse.
 *
 * <p>{@code @Order(100)} leaves room below it for {@code superadmin.SuperAdminSecurityConfig}'s
 * {@code @Order(1)} chain: this one carries no {@code securityMatcher} and so matches everything,
 * and an unordered pair would let it swallow {@code /superadmin/**}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /** PRD §19.1's idle timeout is a container setting; this is the absolute cap on top of it. */
    private static final Duration MAX_SESSION_AGE = Duration.ofHours(24);

    /** CLAUDE.md §6 A02. Cost 12 is a deliberate CPU cost; do not lower it to speed tests up. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * ADMIN implies MANAGER implies EMPLOYEE, so {@code @PreAuthorize} never has to enumerate
     * roles. Must be {@code static}: method-security infrastructure is built early in the context
     * lifecycle, and a non-static bean method here would drag this whole configuration class into
     * premature initialisation.
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN")
                .implies("MANAGER")
                .role("MANAGER")
                .implies("EMPLOYEE")
                .build();
    }

    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy hierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(hierarchy);
        return handler;
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Without this, SessionRegistryImpl never learns that a session was destroyed. */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * Declared as its own bean, not inline in the registration below, so tests and operators can
     * reach {@code clearBuckets}. Boot does not double-register it: a filter already referenced
     * by a {@code FilterRegistrationBean} is excluded from automatic registration.
     */
    @Bean
    RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter(
                SecurityPaths.LOGIN_PATH,
                SecurityPaths.FORGOT_PASSWORD_PATH,
                SecurityPaths.SUPER_ADMIN_LOGIN_PATH);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        // After tenant resolution (HIGHEST_PRECEDENCE) but before the security chain, so a flood
        // of login attempts is turned away without ever reaching the database.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    AbsoluteSessionTimeoutFilter absoluteSessionTimeoutFilter(Clock clock) {
        return new AbsoluteSessionTimeoutFilter(MAX_SESSION_AGE, clock);
    }

    @Bean
    FilterRegistrationBean<AbsoluteSessionTimeoutFilter> absoluteSessionTimeoutFilterRegistration(
            AbsoluteSessionTimeoutFilter filter) {
        FilterRegistrationBean<AbsoluteSessionTimeoutFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    ActorMdcFilter actorMdcFilter() {
        return new ActorMdcFilter();
    }

    /**
     * Order 0 (Boot's plain-servlet-filter default) is comfortably after Spring Security's own
     * chain, which Boot registers at order -100 — so {@code SecurityContextHolder} already holds
     * the authenticated principal, if any, by the time this filter runs (ADR 0017).
     */
    @Bean
    FilterRegistrationBean<ActorMdcFilter> actorMdcFilterRegistration(ActorMdcFilter filter) {
        return new FilterRegistrationBean<>(filter);
    }

    /**
     * Tenant-realm form login over {@code app_user}.
     *
     * <p>The provider is local to this chain — added through {@code http.authenticationProvider},
     * which feeds this {@code HttpSecurity}'s own {@code AuthenticationManagerBuilder} ({@code
     * HttpSecurity} is a prototype bean, so the builder is per-chain). Deliberately <em>not</em> a
     * shared {@code @Bean AuthenticationManager}: a single such bean becomes the parent manager of
     * every chain in the application, so a failed Super Admin login would fall through to it and
     * be retried against this realm's user store, and vice versa. Per-chain keeps the two realms
     * genuinely unable to see each other's credentials.
     *
     * <p>Going through the builder rather than {@code http.authenticationManager(...)} also keeps
     * the {@code AuthenticationEventPublisher} the builder already carries, which is what {@link
     * LoginAttemptListener} listens to for {@code login_audit} rows and the lockout counter.
     */
    @Bean
    @Order(100)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionRegistry sessionRegistry,
            AppUserDetailsService appUsers,
            PasswordEncoder passwordEncoder)
            throws Exception {
        DaoAuthenticationProvider tenantAuthentication = new DaoAuthenticationProvider(appUsers);
        tenantAuthentication.setPasswordEncoder(passwordEncoder);
        tenantAuthentication.afterPropertiesSet();

        http.authenticationProvider(tenantAuthentication)
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(
                                                SecurityPaths.LOGIN_PATH,
                                                SecurityPaths.FORGOT_PASSWORD_PATH,
                                                "/reset-password",
                                                "/accept-invite",
                                                // Token-authenticated, not session-authenticated (CLAUDE.md §6
                                                // A01's deliberate exception — see api.CalendarFeedController's
                                                // Javadoc). The URL-level permitAll() here is what makes the
                                                // controller's own @PreAuthorize("permitAll()") reachable at all.
                                                "/api/v1/calendar/ical.ics",
                                                "/webjars/**",
                                                "/css/**",
                                                "/js/**",
                                                "/img/**",
                                                "/fonts/**",
                                                "/favicon.ico",
                                                "/favicon-16x16.png",
                                                "/favicon-32x32.png",
                                                "/apple-touch-icon.png",
                                                "/actuator/health",
                                                "/error")
                                        .permitAll()
                                        // BootUI surfaces bean/env/config info like actuator does; treat it
                                        // with the same sensitivity as §6 A05's actuator restrictions.
                                        .requestMatchers("/bootui/**")
                                        .hasRole("ADMIN")
                                        // Default-deny (CLAUDE.md §6 A01): anything not listed above needs
                                        // authentication, and @PreAuthorize decides the role on top of it.
                                        .anyRequest()
                                        .authenticated())
                .formLogin(
                        form ->
                                form.loginPage(SecurityPaths.LOGIN_PATH)
                                        .loginProcessingUrl(SecurityPaths.LOGIN_PATH)
                                        .usernameParameter("email")
                                        .defaultSuccessUrl("/", true)
                                        // One generic failure destination for every cause, so the user
                                        // cannot learn whether the address exists, is locked, or is
                                        // disabled (CLAUDE.md §6 A07).
                                        .failureUrl(SecurityPaths.LOGIN_PATH + "?error")
                                        .permitAll())
                .logout(
                        logout ->
                                logout
                                        .logoutUrl("/logout")
                                        .logoutSuccessUrl(SecurityPaths.LOGIN_PATH + "?loggedOut")
                                        .invalidateHttpSession(true)
                                        .deleteCookies("JSESSIONID")
                                        .permitAll())
                .sessionManagement(
                        session ->
                                // A fresh session id on login defeats session fixation: a cookie value
                                // planted before authentication cannot survive into the session.
                                session
                                        .sessionFixation(fixation -> fixation.newSession())
                                        .maximumSessions(-1)
                                        .sessionRegistry(sessionRegistry))
                .headers(SecurityHeaders.standard());

        // CSRF stays at the Spring Security default (on, for every state-changing method). htmx
        // sends the token from the rendered form, so there is no reason to weaken this.
        return http.build();
    }
}
