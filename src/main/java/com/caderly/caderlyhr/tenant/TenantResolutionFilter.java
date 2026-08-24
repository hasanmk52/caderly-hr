package com.caderly.caderlyhr.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter in the chain (PRD §20.3): resolves the tenant from the Host subdomain, populates
 * {@link TenantContext}, and clears it in a finally block so no tenant ever leaks between pooled
 * request threads. Unknown tenant → 404, suspended → 503.
 */
public class TenantResolutionFilter extends OncePerRequestFilter {

    /** Request attribute carrying the resolved {@link TenantSummary} for the web layer. */
    public static final String TENANT_ATTRIBUTE = TenantResolutionFilter.class.getName() + ".TENANT";

    private final TenantFacade tenants;
    private final String baseDomain;

    public TenantResolutionFilter(TenantFacade tenants, String baseDomain) {
        this.tenants = tenants;
        this.baseDomain = baseDomain;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Infrastructure endpoints and static assets are tenant-independent; /error must
        // stay reachable for the 404/503 error dispatch itself.
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/bootui")
                || path.startsWith("/webjars/")
                || path.startsWith("/css/")
                || path.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String slug = extractSlug(request.getServerName());
        if (slug == null) {
            deny(response, HttpServletResponse.SC_NOT_FOUND, "Unknown tenant");
            return;
        }
        Optional<TenantSummary> tenant = tenants.bySlug(slug);
        if (tenant.isEmpty()) {
            deny(response, HttpServletResponse.SC_NOT_FOUND, "Unknown tenant");
            return;
        }
        if (tenant.get().suspended()) {
            deny(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Tenant suspended");
            return;
        }
        TenantContext.set(tenant.get().id());
        request.setAttribute(TENANT_ATTRIBUTE, tenant.get());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    // Writes the generic denial page directly (PRD §20.3) instead of sendError: an
    // error dispatch to /error would be intercepted by the security chain (403) since
    // this filter runs before it. The message is fixed and generic on purpose — it must
    // not leak whether a slug exists.
    private void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("<!DOCTYPE html><html><body><h1>" + message + "</h1></body></html>");
    }

    private @Nullable String extractSlug(String host) {
        // host arrives without the port (ServletRequest#getServerName). Exactly one label
        // before the base domain is a tenant slug; anything else is not resolvable.
        if (!host.endsWith("." + baseDomain)) {
            return null;
        }
        String slug = host.substring(0, host.length() - baseDomain.length() - 1);
        if (slug.isEmpty() || slug.contains(".")) {
            return null;
        }
        return slug;
    }
}
