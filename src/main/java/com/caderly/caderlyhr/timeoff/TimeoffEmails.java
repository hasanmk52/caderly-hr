package com.caderly.caderlyhr.timeoff;

import java.math.BigDecimal;
import org.springframework.web.util.HtmlUtils;

/**
 * The four transactional emails PRD §12.4 names (requested/approved/rejected/cancelled). Copies
 * {@code identity.IdentityEmails}'s exact shape — inline HTML by design, every interpolated value
 * escaped (a leave request's free-text note is user-supplied).
 */
final class TimeoffEmails {

    private TimeoffEmails() {}

    static String requestedSubject(String requesterName) {
        return requesterName + " requested time off";
    }

    static String requestedBody(
            String requesterName, String leaveTypeName, String dateRange, BigDecimal duration, String reviewUrl) {
        return wrap(
                """
                <h2>Time off request</h2>
                <p><strong>%s</strong> has requested %s of %s leave (%s) and is waiting for your \
                approval.</p>
                <p><a href="%s" style="background:#4f46e5;color:#fff;padding:10px 18px;\
                border-radius:8px;text-decoration:none;display:inline-block">Review request</a></p>
                """
                        .formatted(
                                escape(requesterName),
                                escape(duration.toPlainString()),
                                escape(leaveTypeName),
                                escape(dateRange),
                                escape(reviewUrl)));
    }

    static String approvedSubject(String leaveTypeName) {
        return "Your " + leaveTypeName + " request was approved";
    }

    static String approvedBody(String leaveTypeName, String dateRange, String approverName) {
        return wrap(
                """
                <h2>Request approved</h2>
                <p>Your %s request for %s was approved by %s.</p>
                """
                        .formatted(escape(leaveTypeName), escape(dateRange), escape(approverName)));
    }

    static String rejectedSubject(String leaveTypeName) {
        return "Your " + leaveTypeName + " request was rejected";
    }

    static String rejectedBody(
            String leaveTypeName, String dateRange, String approverName, String decisionNote) {
        return wrap(
                """
                <h2>Request rejected</h2>
                <p>Your %s request for %s was rejected by %s.</p>
                %s
                """
                        .formatted(
                                escape(leaveTypeName),
                                escape(dateRange),
                                escape(approverName),
                                decisionNote.isBlank() ? "" : "<p>Note: " + escape(decisionNote) + "</p>"));
    }

    static String cancelledSubject(String requesterName, String leaveTypeName) {
        return requesterName + " cancelled their " + leaveTypeName + " request";
    }

    static String cancelledBody(String requesterName, String leaveTypeName, String dateRange) {
        return wrap(
                """
                <h2>Request cancelled</h2>
                <p><strong>%s</strong> cancelled their %s request for %s.</p>
                """
                        .formatted(escape(requesterName), escape(leaveTypeName), escape(dateRange)));
    }

    private static String wrap(String content) {
        return """
               <!DOCTYPE html>
               <html><body style="font-family:system-ui,-apple-system,'Segoe UI',sans-serif;\
               color:#1F2937;line-height:1.5">
               %s
               <hr style="border:none;border-top:1px solid #E5E7EB;margin:24px 0">
               <p><small style="color:#6C757D">Sent by Caderly HR.</small></p>
               </body></html>
               """
                .formatted(content);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
