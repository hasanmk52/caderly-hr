package com.caderly.caderlyhr.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.support.PlaywrightE2ETestBase;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Home Customize mode end to end (ADR 0021): reorder with the keyboard-accessible buttons (more
 * deterministic than a drag), hide a card, Save, reload, then Reset.
 */
class DashboardCustomizeE2ETest extends PlaywrightE2ETestBase {

    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void employeeReordersAndHidesWidgets_savePersistsAcrossReload_resetRestoresDefault() {
        String slug = "dash-e2e" + UUID.randomUUID().toString().substring(0, 8);
        baseUrl = "http://" + slug + ".localhost:" + port;
        String email = "employee-" + UUID.randomUUID() + "@example.test";
        UUID tenantId =
                TenantContext.runAsSystem(
                        "e2e test: seed tenant", () -> tenants.save(new Tenant(slug, "Dashboard E2E Co")).getId());
        TenantContext.set(tenantId);
        try {
            AppUser employee = AppUser.active(email, passwordEncoder.encode("EmployeePassphrase1"));
            employee.grant(Role.EMPLOYEE);
            appUsers.save(employee);
        } finally {
            TenantContext.clear();
        }

        loginAs(email, "EmployeePassphrase1");
        page.waitForSelector("[data-dashboard-grid]");
        assertThat(visibleOrder()).containsExactly(
                "book-time-off", "my-peers", "time-off-today", "my-days-off", "upcoming-holidays", "resources");

        // Customize: move "resources" to the top (five Up clicks) and hide "my-peers".
        page.click("[data-dashboard-customize]");
        for (int i = 0; i < 5; i++) {
            page.click("[data-widget='resources'] [data-dashboard-move='-1']");
        }
        page.click("[data-widget='my-peers'] [data-dashboard-toggle]");
        page.click("[data-dashboard-save]");
        page.waitForSelector("[data-dashboard-customize]:not(.d-none)");

        // Reload: the saved layout persists, and the hidden card is not rendered at all.
        page.reload();
        page.waitForSelector("[data-dashboard-grid]");
        assertThat(visibleOrder()).containsExactly(
                "resources", "book-time-off", "time-off-today", "my-days-off", "upcoming-holidays");
        // Hidden widgets cost no queries: the placeholder carries no hx-get.
        assertThat(page.locator("[data-widget='my-peers'] [hx-get]").count()).isZero();

        // Cancel discards an unsaved change.
        page.click("[data-dashboard-customize]");
        page.click("[data-widget='resources'] [data-dashboard-move='1']");
        page.click("[data-dashboard-cancel]");
        page.waitForSelector("[data-dashboard-grid]");
        assertThat(visibleOrder().getFirst()).isEqualTo("resources");

        // Reset restores the default order with everything visible.
        page.click("[data-dashboard-customize]");
        page.click("[data-dashboard-reset]");
        page.waitForSelector("[data-dashboard-customize]:not(.d-none)");
        page.reload();
        page.waitForSelector("[data-dashboard-grid]");
        assertThat(visibleOrder()).containsExactly(
                "book-time-off", "my-peers", "time-off-today", "my-days-off", "upcoming-holidays", "resources");
    }

    /** Keys of the cards the user can currently see, in on-screen order. */
    private List<String> visibleOrder() {
        return page.locator("[data-dashboard-grid] > [data-widget]:not([data-hidden='true'])")
                .evaluateAll("els => els.map(e => e.getAttribute('data-widget'))")
                instanceof List<?> keys
                ? keys.stream().map(Object::toString).toList()
                : List.of();
    }
}
