package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.common.NotFoundException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

/**
 * ADR 0013: exception detail text is resolved through {@link org.springframework.context.MessageSource}
 * keyed on {@code "error." + errorCode}, falling back to the exception's own message when no
 * {@code messages.properties} entry exists yet — so the ~35 existing throw sites across services
 * never needed individual edits (CLAUDE.md §11: smallest diff that satisfies the requirement).
 */
class GlobalExceptionHandlerMessageResolutionTest {

    private static final Locale ENGLISH = Locale.ENGLISH;

    @Test
    void handleCaderlyException_whenAMessagesEntryExistsForTheErrorCode_rendersTheExternalizedText() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.department-not-found", ENGLISH, "That department no longer exists");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/organization");
        request.addHeader("Accept", "text/html");

        Object result =
                handler.handleCaderlyException(
                        new NotFoundException("DEPARTMENT_NOT_FOUND", "Department not found"), request);

        assertThat(result).isInstanceOf(ModelAndView.class);
        assertThat(((ModelAndView) result).getModel())
                .containsEntry("detail", "That department no longer exists");
    }

    @Test
    void handleCaderlyException_whenNoMessagesEntryExistsForTheErrorCode_fallsBackToTheExceptionsOwnMessage() {
        StaticMessageSource messages = new StaticMessageSource();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/organization");
        request.addHeader("Accept", "text/html");

        Object result =
                handler.handleCaderlyException(
                        new NotFoundException("DEPARTMENT_NOT_FOUND", "Department not found"), request);

        assertThat(result).isInstanceOf(ModelAndView.class);
        assertThat(((ModelAndView) result).getModel()).containsEntry("detail", "Department not found");
    }

    @Test
    void handleCaderlyException_forAJsonRequest_resolvesTheDetailTheSameWay() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.department-not-found", ENGLISH, "That department no longer exists");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/departments/1");
        request.addHeader("Accept", "application/json");

        Object result =
                handler.handleCaderlyException(
                        new NotFoundException("DEPARTMENT_NOT_FOUND", "Department not found"), request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ProblemDetail problem = (ProblemDetail) ((ResponseEntity<?>) result).getBody();
        assertThat(problem.getDetail()).isEqualTo("That department no longer exists");
    }

    @Test
    void handleMaxUploadSizeExceeded_resolvesThroughMessagesTooWithTheSameFallbackShape() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.upload-too-large", ENGLISH, "That file is too big for Caderly");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/files/upload");
        request.addHeader("Accept", "text/html");

        Object result =
                handler.handleMaxUploadSizeExceeded(
                        new org.springframework.web.multipart.MaxUploadSizeExceededException(30_000_000), request);

        assertThat(((ModelAndView) result).getModel())
                .containsEntry("detail", "That file is too big for Caderly");
    }
}
