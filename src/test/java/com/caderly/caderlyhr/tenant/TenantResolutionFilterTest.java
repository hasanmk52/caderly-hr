package com.caderly.caderlyhr.tenant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class TenantResolutionFilterTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantService tenantService;

    @BeforeEach
    void resetCache() {
        // Tenants seeded mid-test must be visible immediately, and a cached negative
        // lookup from a previous test must not leak into this one.
        tenantService.evictCache();
    }

    @Test
    void home_whenKnownTenantAndAuthenticated_rendersTenantName() throws Exception {
        seedTenantIfAbsent("mhzgroup", "MHZ Group");

        // A principal with a role is required: "/" is the home dashboard,
        // the chain is default-deny, and HomeController requires EMPLOYEE. The tenant assertions
        // are unaffected — tenant resolution runs in a filter ahead of authentication.
        mockMvc
                .perform(
                        get(URI.create("http://mhzgroup.localhost/"))
                                .with(user("someone@mhzgroup.test").roles("EMPLOYEE")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MHZ Group")));
    }

    @Test
    void home_whenKnownTenantButAnonymous_redirectsToLogin() throws Exception {
        seedTenantIfAbsent("mhzgroup", "MHZ Group");

        mockMvc
                .perform(get(URI.create("http://mhzgroup.localhost/")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void home_whenUnknownTenant_returns404() throws Exception {
        mockMvc.perform(get(URI.create("http://nope.localhost/"))).andExpect(status().isNotFound());
    }

    @Test
    void home_whenNoSubdomain_returns404() throws Exception {
        mockMvc.perform(get(URI.create("http://localhost/"))).andExpect(status().isNotFound());
    }

    @Test
    void home_whenSuspendedTenant_returns503() throws Exception {
        Tenant suspended = seedTenantIfAbsent("frozen", "Frozen Co");
        suspended.suspend();
        TenantContext.runAsSystem("test: suspend tenant", () -> tenantRepository.save(suspended));
        tenantService.evictCache();

        mockMvc
                .perform(get(URI.create("http://frozen.localhost/")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void home_whenSuspendedTenant_rendersABrandedPageThatNamesNoTenant() throws Exception {
        Tenant suspended = seedTenantIfAbsent("frozen2", "Frozen Two Co");
        suspended.suspend();
        TenantContext.runAsSystem("test: suspend tenant", () -> tenantRepository.save(suspended));
        tenantService.evictCache();

        // The filter runs before the security chain, so it cannot use the normal layout (it needs a
        // CSRF token); the page must still load the app's own stylesheets and favicon, carry copy
        // from messages.properties, and not echo the tenant's name or slug.
        mockMvc
                .perform(get(URI.create("http://frozen2.localhost/")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Temporarily unavailable")))
                .andExpect(content().string(containsString("/css/caderly.css")))
                .andExpect(content().string(containsString("/favicon.ico")))
                .andExpect(content().string(not(containsString("Frozen Two"))))
                .andExpect(content().string(not(containsString("frozen2"))));
    }

    @Test
    void home_whenUnknownTenant_rendersTheBrandedNotFoundPage() throws Exception {
        mockMvc
                .perform(get(URI.create("http://nope.localhost/")))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Page not found")))
                .andExpect(content().string(containsString("/css/caderly.css")));
    }

    @Test
    void actuatorHealth_withoutTenant_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void staticJsAsset_withoutTenant_returns200() throws Exception {
        // Regression: the Super Admin console (base domain, no tenant subdomain) depends on
        // caderly.js to attach the CSRF header to every htmx request — if this 404s, every
        // suspend/reinstate/delete action there 403s while the page itself still renders fine.
        mockMvc.perform(get("/js/caderly.js")).andExpect(status().isOk());
    }

    @Test
    void favicon_isCacheableNotNoStore() throws Exception {
        // Spring Security adds `no-store` to any response without its own Cache-Control, and a
        // no-store favicon is not kept by the browser — the tab shows no icon.
        mockMvc
                .perform(get("/favicon.ico"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age")))
                .andExpect(header().string("Cache-Control", not(containsString("no-store"))));
    }

    @Test
    void staticFontAsset_withoutTenant_returns200() throws Exception {
        mockMvc.perform(get("/fonts/ibm-plex-sans-latin-400-normal.woff2")).andExpect(status().isOk());
    }

    @Test
    void favicon_withoutTenant_returns200() throws Exception {
        mockMvc.perform(get("/favicon.ico")).andExpect(status().isOk());
    }

    // Seeding a tenant is itself cross-tenant work (there's no tenant yet to be "in") — same
    // reasoning as TenantService#bySlug (ADR 0004): runAsSystem, not a real tenant context.
    private Tenant seedTenantIfAbsent(String slug, String name) {
        return TenantContext.runAsSystem(
                "test: seed tenant fixture",
                () ->
                        tenantRepository
                                .findBySlugAndDeletedAtIsNull(slug)
                                .orElseGet(() -> tenantRepository.save(new Tenant(slug, name))));
    }
}
