package com.caderly.caderlyhr.identity;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.web.util.HtmlUtils;

/**
 * The two transactional emails sub-phase 1.2 sends.
 *
 * <p>Inline HTML by design: tenant-branded Thymeleaf templates and the full event catalogue land
 * in Phase 1.10, and building that machinery for two messages would be speculative.
 *
 * <p>Every interpolated value is HTML-escaped. Thymeleaf's automatic escaping does not apply here
 * because these strings are assembled in Java, and the tenant name is user-supplied.
 *
 * <p>Content is resolved through {@link MessageSource} (ADR 0013) rather than injected as a
 * dependency here — see {@code timeoff.TimeoffEmails}'s identical rationale, including why {@code
 * locale} is always {@link Locale#ENGLISH} at every current call site.
 */
final class IdentityEmails {

    private IdentityEmails() {}

    static String inviteSubject(MessageSource messages, Locale locale, String tenantName) {
        return messages.getMessage(
                "email.invite.subject",
                new Object[] {tenantName},
                "You have been invited to " + tenantName + " on Caderly",
                locale);
    }

    static String inviteBody(MessageSource messages, Locale locale, String tenantName, String acceptUrl) {
        String heading =
                messages.getMessage(
                        "email.invite.body.heading", new Object[] {escape(tenantName)}, "Welcome to {0}", locale);
        String message =
                messages.getMessage(
                        "email.invite.body.message",
                        null,
                        "An administrator has invited you to Caderly. Choose a password to activate your account.",
                        locale);
        String cta = messages.getMessage("email.invite.body.cta", null, "Set your password", locale);
        return wrap(
                messages,
                locale,
                """
                <h2>%s</h2>
                <p>%s</p>
                <p><a href="%s" style="background:#0F5568;color:#fff;padding:10px 18px;\
                border-radius:8px;text-decoration:none;display:inline-block">%s</a></p>
                <p>%s<br><span>%s</span></p>
                <p><small>%s</small></p>
                """
                        .formatted(
                                heading,
                                message,
                                escape(acceptUrl),
                                cta,
                                pasteLink(messages, locale),
                                escape(acceptUrl),
                                expiry(messages, locale)));
    }

    static String resetSubject(MessageSource messages, Locale locale, String tenantName) {
        return messages.getMessage(
                "email.password-reset.subject",
                new Object[] {tenantName},
                "Reset your " + tenantName + " password",
                locale);
    }

    static String resetBody(MessageSource messages, Locale locale, String tenantName, String resetUrl) {
        String heading = messages.getMessage("email.password-reset.body.heading", null, "Password reset", locale);
        String message =
                messages.getMessage(
                        "email.password-reset.body.message",
                        new Object[] {escape(tenantName)},
                        "We received a request to reset your {0} password. If that was you, choose a new one below.",
                        locale);
        String cta = messages.getMessage("email.password-reset.body.cta", null, "Reset password", locale);
        String expiryWithNoop =
                messages.getMessage(
                        "email.password-reset.body.expiry",
                        null,
                        "This link can be used once and expires in 24 hours. If you did not request it, you can"
                                + " ignore this email — your password will not change.",
                        locale);
        return wrap(
                messages,
                locale,
                """
                <h2>%s</h2>
                <p>%s</p>
                <p><a href="%s" style="background:#0F5568;color:#fff;padding:10px 18px;\
                border-radius:8px;text-decoration:none;display:inline-block">%s</a></p>
                <p>%s<br><span>%s</span></p>
                <p><small>%s</small></p>
                """
                        .formatted(
                                heading, message, escape(resetUrl), cta, pasteLink(messages, locale), escape(resetUrl), expiryWithNoop));
    }

    private static String pasteLink(MessageSource messages, Locale locale) {
        return messages.getMessage(
                "email.common.paste-link", null, "Or paste this link into your browser:", locale);
    }

    private static String expiry(MessageSource messages, Locale locale) {
        return messages.getMessage(
                "email.invite.body.expiry", null, "This link can be used once and expires in 24 hours.", locale);
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
