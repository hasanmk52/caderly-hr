package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.tenant.TenantResolutionFilter;
import com.caderly.caderlyhr.tenant.TenantSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the tenant resolved by {@link TenantResolutionFilter} to every Thymeleaf view as {@code
 * tenant}. Null only on tenant-independent paths (e.g. the error page).
 */
@ControllerAdvice
class TenantModelAdvice {

    @ModelAttribute("tenant")
    @Nullable TenantSummary tenant(HttpServletRequest request) {
        return (TenantSummary) request.getAttribute(TenantResolutionFilter.TENANT_ATTRIBUTE);
    }
}
