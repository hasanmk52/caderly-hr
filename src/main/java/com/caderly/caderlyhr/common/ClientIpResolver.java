package com.caderly.caderlyhr.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client IP for a request, shared by {@code security.RateLimitFilter}'s per-IP login
 * limit, the (email + IP) lockout re-key in {@code identity.LoginAttemptService}, and {@code
 * audit.EntityAuditListener}/{@code audit.LoginAuditService} (ADR 0017) — all four must agree on exactly
 * the same address for a given request, or the defenses and the audit trail would be keyed on
 * different things. Lives in {@code common} rather than {@code security} so {@code audit} (which
 * both {@code identity} and {@code security} depend on) never has to depend on {@code security}
 * to reach it.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    /**
     * Trusts {@code X-Forwarded-For} only for its first hop, and only because Caderly always runs
     * behind a reverse proxy that sets it (PRD §27: Caddy). A direct-to-app deployment would make
     * this header attacker-controlled and both the rate limit and the lockout re-key trivially
     * bypassable.
     */
    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        int comma = forwarded.indexOf(',');
        return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
    }
}
