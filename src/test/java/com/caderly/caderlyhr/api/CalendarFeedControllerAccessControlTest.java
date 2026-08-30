package com.caderly.caderlyhr.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.calendar.CalendarTokenService;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The one deliberate exception to session auth (CLAUDE.md §6 A01): token-authenticated instead,
 * confirmed here rather than assumed. Phase 1.8 DoD: a bad/missing token is a clean 4xx, never a
 * 500 or a silent empty-but-200 feed — and this is the one new endpoint outside normal session
 * auth, so its cross-tenant isolation needs its own proof too.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class CalendarFeedControllerAccessControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private EmployeeRepository employees;
    @Autowired private CalendarTokenService tokenService;

    private String slugA;
    private UUID tenantAId;
    private String slugB;
    private UUID tenantBId;

    @BeforeEach
    void seedTenants() {
        slugA = "feed-a" + UUID.randomUUID().toString().substring(0, 8);
        slugB = "feed-b" + UUID.randomUUID().toString().substring(0, 8);
        tenantAId =
                TenantContext.runAsSystem(
                        "test: seed tenant A", () -> tenants.save(new Tenant(slugA, "Feed A Co")).getId());
        tenantBId =
                TenantContext.runAsSystem(
                        "test: seed tenant B", () -> tenants.save(new Tenant(slugB, "Feed B Co")).getId());
    }

    @Test
    void icalFeed_withValidToken_returns200WithCalendarContentType_noSessionRequired() throws Exception {
        String token = issueTokenForNewUserWithEmployee(tenantAId);

        mockMvc
                .perform(url(slugA, "/api/v1/calendar/ical.ics?token=" + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar"));
    }

    @Test
    void icalFeed_withUnknownToken_returns404() throws Exception {
        mockMvc
                .perform(url(slugA, "/api/v1/calendar/ical.ics?token=does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void icalFeed_withMissingTokenParam_returns400() throws Exception {
        mockMvc.perform(url(slugA, "/api/v1/calendar/ical.ics")).andExpect(status().isBadRequest());
    }

    @Test
    void icalFeed_whenTokenBelongsToAnotherTenant_returns404() throws Exception {
        String tokenFromTenantA = issueTokenForNewUserWithEmployee(tenantAId);

        mockMvc
                .perform(url(slugB, "/api/v1/calendar/ical.ics?token=" + tokenFromTenantA))
                .andExpect(status().isNotFound());
    }

    private String issueTokenForNewUserWithEmployee(UUID tenantId) {
        String email = "feed-" + UUID.randomUUID() + "@example.test";
        UUID userId =
                run(tenantId, () -> appUsers.save(AppUser.active(email, "hash"))).getId();
        run(
                tenantId,
                () -> {
                    Employee employee = Employee.create("Feed", "Owner", email);
                    employee.linkUser(userId);
                    return employees.save(employee);
                });
        return run(tenantId, () -> tokenService.getOrCreateToken(userId));
    }

    private <T> T run(UUID tenantId, Supplier<T> action) {
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder url(
            String slug, String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }
}
