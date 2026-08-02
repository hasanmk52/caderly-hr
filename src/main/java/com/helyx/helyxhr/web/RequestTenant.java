package com.helyx.helyxhr.web;

import com.helyx.helyxhr.tenant.TenantResolutionFilter;
import com.helyx.helyxhr.tenant.TenantSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Reads the tenant that {@code TenantResolutionFilter} attached to the request, and builds the
 * absolute URLs that go into outbound email.
 *
 * <p>Links have to be built here rather than in the services: only the web layer knows the
 * request's scheme and host, and an invite link must land on <em>this tenant's</em> subdomain or
 * the recipient cannot log in with it.
 */
final class RequestTenant {

    private RequestTenant() {}

    static TenantSummary of(HttpServletRequest request) {
        TenantSummary tenant =
                (TenantSummary) request.getAttribute(TenantResolutionFilter.TENANT_ATTRIBUTE);
        if (tenant == null) {
            // Unreachable through the filter chain: an unresolved tenant is already a 404.
            throw new IllegalStateException("No tenant on the request");
        }
        return tenant;
    }

    /** Origin of the current request, e.g. {@code https://mhz.helyx.app}. */
    static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
