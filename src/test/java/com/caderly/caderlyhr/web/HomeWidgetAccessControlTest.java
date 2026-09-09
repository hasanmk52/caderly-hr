package com.caderly.caderlyhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserDetailsService;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.net.URI;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CLAUDE.md §8: one 200 test per role plus one anonymous-denied test, for each of the six
 * {@code /widgets/*} fragment endpoints (PRD §24.2, sub-phase 1.9). All six carry the same
 * {@code hasRole('EMPLOYEE')} floor as {@code HomeController.home()} — the role hierarchy passes
 * Manager and Admin through too.
 *
 * <p>Uses a real {@code AppUserPrincipal} (via {@link AppUserDetailsService}), not the generic
 * {@code user(String)} post-processor — three of the six widgets bind {@code @AuthenticationPrincipal
 * AppUserPrincipal}, which only resolves against the real type (see {@code
 * ProfileAccessControlTest}'s javadoc for the same gotcha). None of these accounts link an {@code
 * Employee} row; every widget degrades to its empty state in that case, which is exactly what
 * this class needs — it only asserts the status code, not the content.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class HomeWidgetAccessControlTest {

    private static final String[] WIDGET_PATHS = {
        "/widgets/book-time-off",
        "/widgets/my-peers",
        "/widgets/time-off-today",
        "/widgets/my-days-off",
        "/widgets/upcoming-holidays",
        "/widgets/resources"
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private AppUserDetailsService userDetailsService;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        slug = "widget-rbac" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Widget RBAC Co")).getId());
    }

    @Test
    void widgets_asEmployee_return200() throws Exception {
        UserDetails principal = createUser("employee@widget.test", Role.EMPLOYEE);
        for (String path : WIDGET_PATHS) {
            mockMvc.perform(url(path).with(user(principal))).andExpect(status().isOk());
        }
    }

    @Test
    void widgets_asManager_return200() throws Exception {
        UserDetails principal = createUser("manager@widget.test", Role.MANAGER);
        for (String path : WIDGET_PATHS) {
            mockMvc.perform(url(path).with(user(principal))).andExpect(status().isOk());
        }
    }

    @Test
    void widgets_asAdmin_return200() throws Exception {
        UserDetails principal = createUser("admin@widget.test", Role.ADMIN);
        for (String path : WIDGET_PATHS) {
            mockMvc.perform(url(path).with(user(principal))).andExpect(status().isOk());
        }
    }

    @Test
    void widgets_whenAnonymous_redirectToLogin() throws Exception {
        for (String path : WIDGET_PATHS) {
            mockMvc.perform(url(path)).andExpect(status().is3xxRedirection());
        }
    }

    private UserDetails createUser(String email, Role role) {
        run(
                () -> {
                    AppUser user = AppUser.active(email, "{noop}unused");
                    user.grant(role);
                    return appUsers.save(user);
                });
        return run(() -> userDetailsService.loadUserByUsername(email));
    }

    private <T> T run(Supplier<T> action) {
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }
}
