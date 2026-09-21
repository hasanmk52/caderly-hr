package com.caderly.caderlyhr.superadmin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The allowlist decision on its own, without a Spring context — so the fail-closed case is proved
 * against an <em>actually empty</em> list rather than against whatever {@code application-test.yml}
 * happens to configure. {@code SuperAdminSecurityConfigTest} covers the wiring.
 */
class SuperAdminIpAllowlistFilterTest {

    @Test
    void doFilter_whenAllowlistIsEmpty_deniesEveryone() throws Exception {
        // The behaviour an unset CADERLY_SUPERADMIN_IP_ALLOWLIST must produce: a console nobody
        // can reach, never one everybody can.
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response =
                filter(List.of(), request("/superadmin/tenants", "203.0.113.5"), chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void doFilter_whenClientIsInsideAnAllowedCidr_passesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response =
                filter(List.of("10.0.0.0/8"), request("/superadmin/tenants", "10.4.2.1"), chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void doFilter_whenClientIsOutsideEveryAllowedCidr_isForbidden() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response =
                filter(
                        List.of("10.0.0.0/8", "203.0.113.4/32"),
                        request("/superadmin/tenants", "203.0.113.5"),
                        chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void doFilter_whenPathIsNotTheSuperAdminRealm_passesThroughEvenFromADeniedAddress()
            throws Exception {
        // The operator allowlist must never become an allowlist for the whole application.
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response =
                filter(List.of("10.0.0.0/8"), request("/login", "203.0.113.5"), chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void doFilter_whenBehindAProxy_judgesTheForwardedClientNotTheProxy() throws Exception {
        // ClientIpResolver's first X-Forwarded-For hop, the same address RateLimitFilter keys on.
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("/superadmin/tenants", "10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.9");

        MockHttpServletResponse response = filter(List.of("10.0.0.0/8"), request, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void doFilter_whenTheRealmPathIsPercentEncoded_stillAppliesTheAllowlist() throws Exception {
        // getRequestURI() is undecoded, but securityMatcher("/superadmin/**") matches decoded, so
        // this exact URI used to skip the allowlist and still authenticate a Super Admin.
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response =
                filter(List.of("10.0.0.0/8"), request("/%73uperadmin/login", "203.0.113.5"), chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void doFilter_whenTheRealmPathCarriesMatrixParameters_stillAppliesTheAllowlist() throws Exception {
        // PathPattern strips matrix parameters before matching, so the chain sees /superadmin/login
        // here too — the decoded-path check has to strip them the same way.
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response =
                filter(List.of("10.0.0.0/8"), request("/superadmin;x=y/login", "203.0.113.5"), chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void doFilter_whenAnEncodedSlashWouldFakeTheRealm_doesNotGateTheRequest() throws Exception {
        // The bypass in reverse: %2F decodes *within* a segment, so "/%2Fsuperadmin/login" is one
        // segment named "/superadmin" and is not the realm — the chain agrees, and so must this.
        // Decoding the whole URI as one string (rather than per segment) would get this wrong.
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response =
                filter(List.of("10.0.0.0/8"), request("/%2Fsuperadmin/login", "203.0.113.5"), chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletResponse filter(
            List<String> allowedCidrs, MockHttpServletRequest request, MockFilterChain chain)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SuperAdminIpAllowlistFilter(allowedCidrs).doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String uri, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
