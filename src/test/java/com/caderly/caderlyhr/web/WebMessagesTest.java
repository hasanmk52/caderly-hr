package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.common.NotFoundException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

/** ADR 0013: {@link WebMessages} mirrors {@link GlobalExceptionHandler}'s own key scheme. */
class WebMessagesTest {

    @Test
    void errorDetail_whenAMessagesEntryExistsForTheErrorCode_returnsTheExternalizedText() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.division-name-taken", Locale.ENGLISH, "That division name is already in use");

        String detail =
                WebMessages.errorDetail(
                        messages,
                        new com.caderly.caderlyhr.common.ConflictException("DIVISION_NAME_TAKEN", "Name already in use"),
                        Locale.ENGLISH);

        assertThat(detail).isEqualTo("That division name is already in use");
    }

    @Test
    void errorDetail_whenNoMessagesEntryExists_fallsBackToTheExceptionsOwnMessage() {
        StaticMessageSource messages = new StaticMessageSource();

        String detail =
                WebMessages.errorDetail(
                        messages, new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"), Locale.ENGLISH);

        assertThat(detail).isEqualTo("Employee not found");
    }

    @Test
    void toastTrigger_withAPlainMessage_buildsTheOrganizationToastJson() {
        assertThat(WebMessages.toastTrigger("File uploaded"))
                .isEqualTo("{\"organization-toast\": {\"message\": \"File uploaded\"}}");
    }

    @Test
    void toastTrigger_withQuotesBackslashesAndNonAscii_escapesThemSoTheHeaderStaysValidJson() {
        assertThat(WebMessages.toastTrigger("Say \"hi\" \\ café\n"))
                .isEqualTo("{\"organization-toast\": {\"message\": \"Say \\\"hi\\\" \\\\ caf\\u00e9\\n\"}}");
    }
}
