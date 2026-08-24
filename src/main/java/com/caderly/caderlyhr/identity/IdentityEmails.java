package com.caderly.caderlyhr.identity;

import org.springframework.web.util.HtmlUtils;

/**
 * The two transactional emails sub-phase 1.2 sends.
 *
 * <p>Inline HTML by design: tenant-branded Thymeleaf templates and the full event catalogue land
 * in Phase 1.10, and building that machinery for two messages would be speculative.
 *
 * <p>Every interpolated value is HTML-escaped. Thymeleaf's automatic escaping does not apply here
 * because these strings are assembled in Java, and the tenant name is user-supplied.
 */
final class IdentityEmails {

    private IdentityEmails() {}

    static String inviteSubject(String tenantName) {
        return "You have been invited to " + tenantName + " on Caderly";
    }

    static String inviteBody(String tenantName, String acceptUrl) {
        return wrap(
                """
                <h2>Welcome to %s</h2>
                <p>An administrator has invited you to Caderly. Choose a password to activate your \
                account.</p>
                <p><a href="%s" style="background:#4f46e5;color:#fff;padding:10px 18px;\
                border-radius:8px;text-decoration:none;display:inline-block">Set your password</a></p>
                <p>Or paste this link into your browser:<br><span>%s</span></p>
                <p><small>This link can be used once and expires in 24 hours.</small></p>
                """
                        .formatted(escape(tenantName), escape(acceptUrl), escape(acceptUrl)));
    }

    static String resetSubject(String tenantName) {
        return "Reset your " + tenantName + " password";
    }

    static String resetBody(String tenantName, String resetUrl) {
        return wrap(
                """
                <h2>Password reset</h2>
                <p>We received a request to reset your %s password. If that was you, choose a new \
                one below.</p>
                <p><a href="%s" style="background:#4f46e5;color:#fff;padding:10px 18px;\
                border-radius:8px;text-decoration:none;display:inline-block">Reset password</a></p>
                <p>Or paste this link into your browser:<br><span>%s</span></p>
                <p><small>This link can be used once and expires in 24 hours. If you did not \
                request it, you can ignore this email — your password will not change.</small></p>
                """
                        .formatted(escape(tenantName), escape(resetUrl), escape(resetUrl)));
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
