package com.helyx.helyxhr.security;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Authentication and authorization (PRD §19.1, §19.2, §19.6; CLAUDE.md §6). Shape agreed in ADR
 * 0006 before implementation, per the CLAUDE.md §12 ask-first rule.
 *
 * <p>Notably absent: a custom {@code AuthenticationProvider}. Stock {@code
 * DaoAuthenticationProvider} over {@code AppUserDetailsService} is already tenant-scoped, because
 * {@code AppUser} carries {@code @TenantId} — see ADR 0006 decision A.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    static final String LOGIN_PATH = "/login";
    static final String FORGOT_PASSWORD_PATH = "/forgot-password";

    /** PRD §19.1's idle timeout is a container setting; this is the absolute cap on top of it. */
    private static final Duration MAX_SESSION_AGE = Duration.ofHours(24);

    /**
     * PRD §19.6, tightened. The PRD's draft allows cdn.jsdelivr.net and unpkg.com, but every asset
     * is served from WebJars on our own origin, so those hosts are dropped.
     *
     * <p>Deliberately no {@code 'unsafe-eval'}: Alpine.js needs it for expression evaluation, but
     * nothing in the app uses Alpine yet (no {@code x-data} anywhere). The first template that
     * does will break visibly in the browser console, and the fix at that point is to adopt
     * Alpine's CSP build rather than to weaken this directive.
     *
     * <p>{@code 'unsafe-inline'} remains on style-src only, which Bootstrap components require.
     */
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; "
                    + "script-src 'self'; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; "
                    + "font-src 'self'; "
                    + "form-action 'self'; "
                    + "base-uri 'self'; "
                    + "frame-ancestors 'none'";

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
        return new RateLimitFilter(LOGIN_PATH, FORGOT_PASSWORD_PATH);
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry)
            throws Exception {
        http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(
                                                LOGIN_PATH,
                                                FORGOT_PASSWORD_PATH,
                                                "/reset-password",
                                                "/accept-invite",
                                                "/webjars/**",
                                                "/css/**",
                                                "/js/**",
                                                "/img/**",
                                                "/favicon.ico",
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
                                form.loginPage(LOGIN_PATH)
                                        .loginProcessingUrl(LOGIN_PATH)
                                        .usernameParameter("email")
                                        .defaultSuccessUrl("/", true)
                                        // One generic failure destination for every cause, so the user
                                        // cannot learn whether the address exists, is locked, or is
                                        // disabled (CLAUDE.md §6 A07).
                                        .failureUrl(LOGIN_PATH + "?error")
                                        .permitAll())
                .logout(
                        logout ->
                                logout
                                        .logoutUrl("/logout")
                                        .logoutSuccessUrl(LOGIN_PATH + "?loggedOut")
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
                .headers(
                        headers ->
                                headers
                                        .frameOptions(frame -> frame.deny())
                                        .xssProtection(
                                                xss ->
                                                        // The legacy XSS auditor is disabled on purpose: it is
                                                        // removed from modern browsers and, where it survives,
                                                        // has introduced vulnerabilities of its own. CSP above
                                                        // is the actual defence.
                                                        xss.headerValue(
                                                                XXssProtectionHeaderWriter.HeaderValue.DISABLED))
                                        .contentSecurityPolicy(
                                                csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                        .permissionsPolicyHeader(
                                                permissions ->
                                                        permissions.policy(
                                                                "accelerometer=(), camera=(), geolocation=(),"
                                                                    + " gyroscope=(), magnetometer=(), microphone=(),"
                                                                    + " payment=(), usb=()"))
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .preload(true)
                                                                .maxAgeInSeconds(Duration.ofDays(365).toSeconds())));

        // CSRF stays at the Spring Security default (on, for every state-changing method). htmx
        // sends the token from the rendered form, so there is no reason to weaken this.
        return http.build();
    }
}
