package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

/**
 * CLAUDE.md §6 A03 / CURRENT_PHASE.md DoD: an oversize upload must get a clear error, never a raw
 * 500 or the Whitelabel page.
 *
 * <p>This is a direct unit test of {@link GlobalExceptionHandler#handleMaxUploadSizeExceeded}, not
 * a full MockMvc round trip. Confirmed empirically (see the git history of this file): MockMvc's
 * default {@code webEnvironment = MOCK} never wires a real {@code MultipartConfigElement} onto the
 * mock request, so {@code MockMultipartHttpServletRequestBuilder} bypasses container-level size
 * enforcement entirely regardless of {@code spring.servlet.multipart.max-file-size} — the
 * exception this handler exists for is simply never thrown, and the test that tried it reached the
 * controller body instead. Proving the servlet actually aborts the request would need a {@code
 * RANDOM_PORT} server and a real multipart HTTP client; what this test proves instead is the one
 * thing under this codebase's control if that exception does reach here — the handler renders 413
 * with a real Caderly page.
 */
class MaxUploadSizeExceededTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new StaticMessageSource());

    @Test
    void handleMaxUploadSizeExceeded_forABrowserRequest_rendersACaderlyErrorPage() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/files/upload");
        request.addHeader("Accept", "text/html");

        Object result = handler.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(30_000_000), request);

        assertThat(result).isInstanceOf(ModelAndView.class);
        ModelAndView modelAndView = (ModelAndView) result;
        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(modelAndView.getViewName()).isEqualTo("error/error");
        assertThat(modelAndView.getModel()).containsEntry("title", "Content Too Large");
        assertThat(modelAndView.getModel().get("detail")).asString().doesNotContain("MaxUploadSizeExceededException");
    }

    @Test
    void handleMaxUploadSizeExceeded_forAJsonRequest_returnsAProblemDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/files/upload");
        request.addHeader("Accept", "application/json");

        Object result = handler.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(30_000_000), request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }
}
