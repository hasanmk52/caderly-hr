package com.caderly.caderlyhr.timeoff;

import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.web.util.HtmlUtils;

/**
 * The four transactional emails PRD §12.4 names (requested/approved/rejected/cancelled). Copies
 * {@code identity.IdentityEmails}'s exact shape — inline HTML by design, every interpolated value
 * escaped (a leave request's free-text note is user-supplied).
 *
 * <p>Content is resolved through {@link MessageSource} (ADR 0013) rather than injected as a
 * dependency here: these stay stateless static functions, and {@code messages}/{@code locale} are
 * threaded through as explicit parameters from the calling {@code @Service}, which already holds
 * (or gains) a constructor-injected {@code MessageSource}. {@code locale} is always {@link
 * Locale#ENGLISH} at every current call site — email content is built at outbox enqueue time,
 * which is architecturally a background/outbox concern (CLAUDE.md §6a) even though today it
 * happens to run inside the triggering request thread, so the locale is a hardcoded constant, not
 * read from request-scoped context (ADR 0013 Decision D).
 */
final class TimeoffEmails {

    private TimeoffEmails() {}

    static String requestedSubject(MessageSource messages, Locale locale, String requesterName) {
        return messages.getMessage(
                "email.leave-requested.subject",
                new Object[] {requesterName},
                requesterName + " requested time off",
                locale);
    }

    static String requestedBody(
            MessageSource messages,
            Locale locale,
            String requesterName,
            String leaveTypeName,
            String dateRange,
            BigDecimal duration,
            String reviewUrl) {
        String heading = messages.getMessage("email.leave-requested.body.heading", null, "Time off request", locale);
        String message =
                messages.getMessage(
                        "email.leave-requested.body.message",
                        new Object[] {escape(requesterName), escape(duration.toPlainString()), escape(leaveTypeName), escape(dateRange)},
                        "<strong>{0}</strong> has requested {1} of {2} leave ({3}) and is waiting for your approval.",
                        locale);
        String cta = messages.getMessage("email.leave-requested.body.cta", null, "Review request", locale);
        return wrap(
                messages,
                locale,
                """
                <h2>%s</h2>
                <p>%s</p>
                <p><a href="%s" style="background:#4f46e5;color:#fff;padding:10px 18px;\
                border-radius:8px;text-decoration:none;display:inline-block">%s</a></p>
                """
                        .formatted(heading, message, escape(reviewUrl), cta));
    }

    static String approvedSubject(MessageSource messages, Locale locale, String leaveTypeName) {
        return messages.getMessage(
                "email.leave-approved.subject",
                new Object[] {leaveTypeName},
                "Your " + leaveTypeName + " request was approved",
                locale);
    }

    static String approvedBody(
            MessageSource messages, Locale locale, String leaveTypeName, String dateRange, String approverName) {
        String heading = messages.getMessage("email.leave-approved.body.heading", null, "Request approved", locale);
        String message =
                messages.getMessage(
                        "email.leave-approved.body.message",
                        new Object[] {escape(leaveTypeName), escape(dateRange), escape(approverName)},
                        "Your {0} request for {1} was approved by {2}.",
                        locale);
        return wrap(messages, locale, "<h2>%s</h2>\n<p>%s</p>\n".formatted(heading, message));
    }

    static String rejectedSubject(MessageSource messages, Locale locale, String leaveTypeName) {
        return messages.getMessage(
                "email.leave-rejected.subject",
                new Object[] {leaveTypeName},
                "Your " + leaveTypeName + " request was rejected",
                locale);
    }

    static String rejectedBody(
            MessageSource messages,
            Locale locale,
            String leaveTypeName,
            String dateRange,
            String approverName,
            String decisionNote) {
        String heading = messages.getMessage("email.leave-rejected.body.heading", null, "Request rejected", locale);
        String message =
                messages.getMessage(
                        "email.leave-rejected.body.message",
                        new Object[] {escape(leaveTypeName), escape(dateRange), escape(approverName)},
                        "Your {0} request for {1} was rejected by {2}.",
                        locale);
        String note =
                decisionNote.isBlank()
                        ? ""
                        : "<p>"
                                + messages.getMessage(
                                        "email.leave-rejected.body.note",
                                        new Object[] {escape(decisionNote)},
                                        "Note: {0}",
                                        locale)
                                + "</p>";
        return wrap(messages, locale, "<h2>%s</h2>\n<p>%s</p>\n%s\n".formatted(heading, message, note));
    }

    static String cancelledSubject(MessageSource messages, Locale locale, String requesterName, String leaveTypeName) {
        return messages.getMessage(
                "email.leave-cancelled.subject",
                new Object[] {requesterName, leaveTypeName},
                requesterName + " cancelled their " + leaveTypeName + " request",
                locale);
    }

    static String cancelledBody(
            MessageSource messages, Locale locale, String requesterName, String leaveTypeName, String dateRange) {
        String heading = messages.getMessage("email.leave-cancelled.body.heading", null, "Request cancelled", locale);
        String message =
                messages.getMessage(
                        "email.leave-cancelled.body.message",
                        new Object[] {escape(requesterName), escape(leaveTypeName), escape(dateRange)},
                        "<strong>{0}</strong> cancelled their {1} request for {2}.",
                        locale);
        return wrap(messages, locale, "<h2>%s</h2>\n<p>%s</p>\n".formatted(heading, message));
    }

    private static String wrap(MessageSource messages, Locale locale, String content) {
        String footer = messages.getMessage("email.common.footer", null, "Sent by Caderly HR.", locale);
        return """
               <!DOCTYPE html>
               <html><body style="font-family:system-ui,-apple-system,'Segoe UI',sans-serif;\
               color:#1F2937;line-height:1.5">
               %s
               <hr style="border:none;border-top:1px solid #E5E7EB;margin:24px 0">
               <p><small style="color:#6C757D">%s</small></p>
               </body></html>
               """
                .formatted(content, footer);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
