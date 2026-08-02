package com.helyx.helyxhr.tenant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helyx.helyxhr.TestcontainersConfiguration;
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
    void home_whenKnownTenant_rendersTenantName() throws Exception {
        seedTenantIfAbsent("mhz", "MHZ Software");

        mockMvc
                .perform(get(URI.create("http://mhz.localhost/")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MHZ Software")));
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
    void actuatorHealth_withoutTenant_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
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
