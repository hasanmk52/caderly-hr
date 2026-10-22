package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.security.RateLimitFilter;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import com.caderly.caderlyhr.tenant.TenantResolutionFilter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The public site's security boundary (ADR 0022 decisions A and B): its pages are open on the base
 * domain, nothing else is reachable there, and none of it leaks onto a tenant subdomain.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class PublicSiteAccessControlTest {

    private static final String SITE = "http://localhost";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private RateLimitFilter rateLimitFilter;

    private String tenantSlug;

    @BeforeEach
    void seedTenant() {
        rateLimitFilter.clearBuckets(null);
        tenantSlug = "pubsite-" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.runAsSystem("test: seed tenant", () -> tenants.save(new Tenant(tenantSlug, "Pubsite Co")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/signup", "/login", "/terms", "/privacy", "/dpa", "/signup/sent"})
    void publicPages_onTheBaseDomain_areOpenToAnonymousVisitors(String path) throws Exception {
        mockMvc.perform(get(URI.create(SITE + path)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/site.css")));
    }

    @Test
    void home_onTheBaseDomain_rendersTheSiteNotTheTenantDashboard() throws Exception {
        mockMvc.perform(get(URI.create(SITE + "/")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("HR software that shows its arithmetic.")))
                .andExpect(content().string(not(containsString("caderly-sidebar"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/people", "/admin/users", "/files", "/accept-invite?token=x", "/api/v1/calendar/ical.ics"})
    void tenantRoutes_onTheBaseDomain_doNotExist(String path) throws Exception {
        mockMvc.perform(get(URI.create(SITE + path))).andExpect(status().isNotFound());
    }

    @Test
    void tenantRoutes_onTheBaseDomain_stayHiddenEvenForASignedInUser() throws Exception {
        mockMvc.perform(get(URI.create(SITE + "/people")).with(user("x@y.test").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void signupPage_onATenantSubdomain_isNotServed() throws Exception {
        // On a tenant host /signup is just another unknown, protected path: anonymous → login.
        mockMvc.perform(get(URI.create("http://" + tenantSlug + ".localhost/signup")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void login_onATenantSubdomain_isStillTheTenantSignInForm() throws Exception {
        mockMvc.perform(get(URI.create("http://" + tenantSlug + ".localhost/login")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(not(containsString("/css/site.css"))));
    }

    @Test
    void signup_withoutCsrfToken_isRefused() throws Exception {
        mockMvc.perform(signupPost(uniqueSlug(), uniqueEmail())).andExpect(status().isForbidden());
    }

    @Test
    void signup_withAValidForm_redirectsToTheSentPageAndQueuesTheEmail() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(signupPost(uniqueSlug(), email).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/signup/sent"))
                .andExpect(flash().attribute("sentTo", email));

        assertThat(mailCount(email)).isEqualTo(1);
    }

    @Test
    void signup_whenTheHoneypotIsFilled_looksSuccessfulButSendsNothing() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(signupPost(uniqueSlug(), email).param("website", "http://spam.example").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/signup/sent"));

        assertThat(mailCount(email)).isZero();
    }

    @Test
    void signup_withAReservedAddress_reRendersTheFormWithTheReason() throws Exception {
        mockMvc.perform(signupPost("admin", uniqueEmail()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("kept for Caderly itself")));
    }

    @Test
    void signup_withoutAcceptingTheTerms_reRendersTheForm() throws Exception {
        mockMvc.perform(signupPost(uniqueSlug(), uniqueEmail(), false).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Accept the terms")));
    }

    @Test
    void signup_afterFiveAttemptsFromOneAddress_isRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(signupPost(uniqueSlug(), uniqueEmail()).with(csrf()));
        }
        mockMvc.perform(signupPost(uniqueSlug(), uniqueEmail()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/signup?rateLimited"));
    }

    @Test
    void checkAddress_reportsAvailabilityAsJson() throws Exception {
        mockMvc.perform(sitePost("/signup/check-address").param("slug", tenantSlug).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.status").value("TAKEN"));
        mockMvc.perform(sitePost("/signup/check-address").param("slug", uniqueSlug()).with(csrf()))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void verify_withAnUnknownToken_saysTheLinkIsNoLongerValid() throws Exception {
        mockMvc.perform(get(URI.create(SITE + "/signup/verify?token=nope")))
                .andExpect(status().isGone())
                .andExpect(content().string(containsString("expired or was already used")));
    }

    @Test
    void verify_postWithAnUnknownToken_saysTheLinkIsNoLongerValid() throws Exception {
        mockMvc.perform(sitePost("/signup/verify").param("token", "nope").with(csrf()))
                .andExpect(status().isGone())
                .andExpect(content().string(containsString("expired or was already used")));
    }

    /** Mail scanners open links before people do; opening one must not spend it (ADR 0022 decision C). */
    @Test
    void verify_get_showsAConfirmButtonAndLeavesTheLinkUsable() throws Exception {
        String slug = uniqueSlug();
        String email = uniqueEmail();
        mockMvc.perform(signupPost(slug, email).with(csrf())).andExpect(status().is3xxRedirection());
        String token = tokenInMailTo(email);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(URI.create(SITE + "/signup/verify")).param("token", token))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(slug + ".localhost")))
                    .andExpect(content().string(containsString("id=\"confirm-signup\"")));
        }

        String acceptUrl =
                (String)
                        mockMvc.perform(sitePost("/signup/verify").param("token", token).with(csrf()))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/signup/created"))
                                .andReturn()
                                .getFlashMap()
                                .get("acceptUrl");
        assertThat(acceptUrl).startsWith("http://" + slug + ".localhost/accept-invite?token=");
    }

    /**
     * The ready page links to the workspace instead of the POST redirecting there: CSP
     * {@code form-action 'self'} blocks cross-origin redirects after a form post.
     */
    @Test
    void signupCreated_rendersTheSetPasswordLinkFromTheFlash() throws Exception {
        String acceptUrl = "http://acme.localhost/accept-invite?token=abc";
        mockMvc.perform(get(URI.create(SITE + "/signup/created"))
                        .flashAttr("acceptUrl", acceptUrl)
                        .flashAttr("workspaceHost", "acme.localhost"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + acceptUrl + "\"")));
    }

    @Test
    void signupCreated_withoutAFreshConfirmation_goesToSignIn() throws Exception {
        mockMvc.perform(get(URI.create(SITE + "/signup/created")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void verify_postWithoutCsrfToken_isRefused() throws Exception {
        mockMvc.perform(sitePost("/signup/verify").param("token", "nope")).andExpect(status().isForbidden());
    }

    @Test
    void loginGo_withAWorkspaceAddress_redirectsToThatWorkspacesSignIn() throws Exception {
        mockMvc.perform(get(URI.create(SITE + "/login/go?workspace=Acme")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://acme.localhost/login"));
    }

    @Test
    void loginGo_acceptsTheFullAddressToo() throws Exception {
        mockMvc.perform(get(URI.create(SITE + "/login/go?workspace=acme.localhost")))
                .andExpect(redirectedUrl("http://acme.localhost/login"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example/x", "acme.evil.example", "", "www"})
    void loginGo_withAnythingElse_staysOnThePageInsteadOfRedirecting(String workspace) throws Exception {
        mockMvc.perform(get(URI.create(SITE + "/login/go")).param("workspace", workspace))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("isn&#39;t a workspace address")));
    }

    /**
     * A POST to the base domain, already tagged as the public site. In a real request {@link
     * TenantResolutionFilter} tags it before Spring Security runs; MockMvc's {@code csrf()} post
     * processor picks its token repository by matching filter chains before any filter has run, so
     * without the tag it would read the tenant chain's repository.
     */
    private static MockHttpServletRequestBuilder sitePost(String path) {
        return post(URI.create(SITE + path)).requestAttr(TenantResolutionFilter.PUBLIC_SITE_ATTRIBUTE, Boolean.TRUE);
    }

    private static MockHttpServletRequestBuilder signupPost(String slug, String email) {
        return signupPost(slug, email, true);
    }

    private static MockHttpServletRequestBuilder signupPost(String slug, String email, boolean acceptTerms) {
        return sitePost("/signup")
                .param("companyName", "Acme Trading LLC")
                .param("slug", slug)
                .param("adminName", "Aisha Rahman")
                .param("adminEmail", email)
                .param("timezone", "Asia/Dubai")
                .param("weekend", "SAT_SUN")
                .param("acceptTerms", String.valueOf(acceptTerms));
    }

    private long mailCount(String email) {
        return TenantContext.runAsSystem(
                "test: read outbox", () -> outbox.findAll().stream().filter(r -> r.toEmail().equals(email)).count());
    }

    private String tokenInMailTo(String email) {
        String body =
                TenantContext.runAsSystem(
                        "test: read outbox",
                        () ->
                                outbox.findAll().stream()
                                        .filter(r -> r.toEmail().equals(email))
                                        .reduce((first, second) -> second)
                                        .orElseThrow()
                                        .bodyHtml());
        Matcher matcher = Pattern.compile("[?&]token=([A-Za-z0-9_%\\-]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private static String uniqueSlug() {
        return "web-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniqueEmail() {
        return "visitor-" + UUID.randomUUID() + "@example.test";
    }
}
