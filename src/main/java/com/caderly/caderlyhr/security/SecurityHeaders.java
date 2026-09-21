package com.caderly.caderlyhr.security;

import java.time.Duration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;

/**
 * The response-header hardening required by PRD §19.6 / CLAUDE.md §6 A05, as one reusable {@link
 * Customizer}.
 *
 * <p>Extracted from {@code SecurityConfig} when the Super Admin realm gained a second {@link
 * org.springframework.security.web.SecurityFilterChain} (sub-phase 1.13). Two chains mean two
 * {@code .headers(...)} blocks, and headers are exactly the kind of thing that drifts when it is
 * written twice — a CSP tightened on one chain and forgotten on the other is invisible until
 * something is already being exfiltrated. One method, called from both, makes that impossible.
 */
public final class SecurityHeaders {

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

    private SecurityHeaders() {}

    /** Apply to every chain: {@code http.headers(SecurityHeaders.standard())}. */
    public static Customizer<HeadersConfigurer<HttpSecurity>> standard() {
        return headers ->
                headers
                        .frameOptions(frame -> frame.deny())
                        .xssProtection(
                                xss ->
                                        // The legacy XSS auditor is disabled on purpose: it is removed from
                                        // modern browsers and, where it survives, has introduced
                                        // vulnerabilities of its own. CSP above is the actual defence.
                                        xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.DISABLED))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(
                                referrer ->
                                        referrer.policy(
                                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(
                                permissions ->
                                        permissions.policy(
                                                "accelerometer=(), camera=(), geolocation=(), gyroscope=(),"
                                                    + " magnetometer=(), microphone=(), payment=(), usb=()"))
                        .httpStrictTransportSecurity(
                                hsts ->
                                        hsts.includeSubDomains(true)
                                                .preload(true)
                                                .maxAgeInSeconds(Duration.ofDays(365).toSeconds()));
    }
}
