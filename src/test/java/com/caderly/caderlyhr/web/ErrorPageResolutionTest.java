package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.ModelAndView;

/**
 * Every error status Caderly can return must land on a Caderly page.
 *
 * <p>This exists because it did not. Sub-phase 1.2 shipped {@code error/404.html} and {@code
 * error/5xx.html} only, and Boot's {@code DefaultErrorViewResolver} looks for {@code
 * error/<status>} then {@code error/<series>xx} — so a 403 matched neither and fell through to the
 * Whitelabel Error Page. With devtools on the classpath (which raises {@code
 * server.error.include-stacktrace} to ALWAYS) that page printed a full stack trace.
 *
 * <p>The RBAC tests did not catch it: they assert the status code, and the status code was right.
 * It was the rendered page that was wrong.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class ErrorPageResolutionTest {

    @Autowired private ErrorViewResolver errorViewResolver;

    // spring.web.error.* binds to WebProperties#getError; ErrorProperties is not a bean itself.
    @Autowired private WebProperties webProperties;
    @Autowired private Environment environment;

    private ModelAndView resolve(int status) {
        return errorViewResolver.resolveErrorView(
                new MockHttpServletRequest(), HttpStatus.valueOf(status), Map.of());
    }

    // One per resolution path rather than an exhaustive status list: the two with dedicated
    // templates, one other 4xx and one other 5xx for the series catch-alls. Adding 415 and 502
    // would re-test the same two lookups.
    @ParameterizedTest
    @ValueSource(ints = {403, 404, 409, 500})
    void resolveErrorView_forAnyStatusWeCanReturn_findsACaderlyTemplate(int status) {
        assertThat(resolve(status))
                .as(
                        "HTTP %d has no error template, so Boot falls back to the Whitelabel page. "
                                + "Add error/%d.html, or rely on the error/4xx.html and error/5xx.html "
                                + "catch-alls.",
                        status, status)
                .isNotNull();
    }

    @Test
    void resolveErrorView_forAnExactStatusTemplate_prefersItOverTheSeriesCatchAll() {
        // 403 and 404 have dedicated wording; the 4xx catch-all must not shadow them.
        assertThat(resolve(403).getViewName()).isEqualTo("error/403");
        assertThat(resolve(404).getViewName()).isEqualTo("error/404");
        assertThat(resolve(409).getViewName()).isEqualTo("error/4xx");
    }

    /**
     * Error responses must never carry a stack trace, an exception class, or binding details
     * (CLAUDE.md §6 A05). Asserted against the bound {@link ErrorProperties} rather than the YAML
     * text, because the failure this guards against is precisely a setting that <em>looks</em>
     * right and binds to nothing: these keys moved from {@code server.error.*} to {@code
     * spring.web.error.*} in Boot 4.0.0, and the old names are deprecated at level=error, so Boot
     * ignores them without a warning. Only the bound object proves the value arrived.
     *
     * <p>It also has to be pinned rather than left to Boot's default, because devtools raises all
     * of these to ALWAYS — which is how a 403 came to return the full filter chain to the client.
     */
    @Test
    void errorResponses_neverIncludeStacktraceExceptionOrMessage() {
        ErrorProperties errorProperties = webProperties.getError();
        assertThat(errorProperties.getIncludeStacktrace())
                .as("a stack trace in an error response hands an attacker the internals")
                .isEqualTo(ErrorProperties.IncludeAttribute.NEVER);
        assertThat(errorProperties.isIncludeException()).isFalse();
        assertThat(errorProperties.getIncludeMessage())
                .isEqualTo(ErrorProperties.IncludeAttribute.NEVER);
        assertThat(errorProperties.getIncludeBindingErrors())
                .isEqualTo(ErrorProperties.IncludeAttribute.NEVER);
    }

    /**
     * The assertion above cannot catch every way this regresses. Boot's own default is already
     * NEVER, and devtools does not contribute its property defaults inside a test context — so if
     * someone "fixed" the config back to the deprecated {@code server.error.*} namespace, the
     * bound value would still read NEVER here and the test would pass, while dev and any
     * devtools-enabled environment quietly started leaking stack traces again.
     *
     * <p>So this asserts the key is set under the namespace Boot 4 actually reads, and that the
     * dead one has not crept back.
     */
    @Test
    void errorProperties_areSetUnderTheNamespaceBootFourReads() {
        assertThat(environment.getProperty("spring.web.error.include-stacktrace"))
                .as("must be pinned explicitly — devtools raises the default to ALWAYS")
                .isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-stacktrace"))
                .as("deprecated at level=error in Boot 4.0.0; silently ignored, so it is a trap")
                .isNull();
    }
}
