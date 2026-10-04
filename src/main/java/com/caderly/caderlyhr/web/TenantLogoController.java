package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.tenant.TenantFacade;
import com.caderly.caderlyhr.tenant.TenantFacade.TenantLogo;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the current tenant's uploaded logo (ADR 0020). Public because the login page shows it
 * before anyone has a session; the tenant comes from the subdomain via {@code
 * TenantResolutionFilter}, so a host can only ever read its own tenant's logo. The content type
 * is the Tika-detected one stored at upload, and {@code nosniff} stops a browser reinterpreting
 * the bytes. The URL carries the content version, so a year-long immutable cache is safe: a new
 * upload changes the URL.
 */
@Controller
class TenantLogoController {

    private final TenantFacade tenants;

    TenantLogoController(TenantFacade tenants) {
        this.tenants = tenants;
    }

    @GetMapping("/tenant-logo")
    @PreAuthorize("permitAll()")
    ResponseEntity<byte[]> logo() {
        return tenants.currentLogo()
                .map(TenantLogoController::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<byte[]> ok(TenantLogo logo) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(logo.content());
    }
}
