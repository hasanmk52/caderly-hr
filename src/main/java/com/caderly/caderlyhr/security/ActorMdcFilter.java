package com.caderly.caderlyhr.security;

import com.caderly.caderlyhr.common.AuditActor;
import com.caderly.caderlyhr.common.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The third leg of CLAUDE.md §6 A09's MDC correlation (ADR 0017) — {@code requestId}/{@code
 * tenantId} come from {@code tenant.TenantResolutionFilter}, which runs before authentication;
 * {@code actorId} can only be known once authentication has run, so this filter is registered
 * <em>after</em> Spring Security's own filter chain ({@code SecurityConfig}'s registration order),
 * not alongside {@code TenantResolutionFilter}.
 *
 * <p>Unauthenticated requests (the login page itself, public error pages) simply carry no {@code
 * actorId} — there is no actor yet, not a value to fall back to.
 */
public class ActorMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean actorSet = false;
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuditActor actor) {
            MDC.put(MdcKeys.ACTOR_ID, actor.actorId().toString());
            actorSet = true;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (actorSet) {
                MDC.remove(MdcKeys.ACTOR_ID);
            }
        }
    }
}
