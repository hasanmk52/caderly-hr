package com.caderly.caderlyhr.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every event in PRD §17.2 must actually render. The failure this guards against is quiet: a
 * template that references a message key nobody added renders {@code ??email.foo.bar_en??} into a
 * customer's inbox and throws nothing, and a template whose name does not match its {@link
 * EmailEvent} only fails at the moment the event first fires — which for a birthday reminder could
 * be months after the code shipped.
 */
class EmailTemplateRenderTest extends TenantIsolationTestBase {

    /** Thymeleaf's rendering of a message key it could not resolve. */
    private static final String UNRESOLVED_MESSAGE_MARKER = "??";

    @Autowired private EmailTemplateService templates;

    @ParameterizedTest
    @EnumSource(EmailEvent.class)
    void render_forEveryEvent_producesABrandedBodyWithNoUnresolvedKeys(EmailEvent event) {
        String html = asTenant(tenantA, () -> templates.render(event, modelFor(event)));

        assertThat(html)
                .as("%s renders a complete document", event)
                .contains("<!DOCTYPE html>")
                .contains("</html>");
        assertThat(html).as("%s names the tenant in its chrome", event).contains("Tenant A");
        assertThat(html).as("%s carries the Caderly brand colour", event).contains("#0F5568");
        assertThat(html)
                .as(
                        "%s references a messages.properties key that does not exist — Thymeleaf renders"
                                + " those as ??key_locale?? rather than failing",
                        event)
                .doesNotContain(UNRESOLVED_MESSAGE_MARKER);
    }

    @Test
    void render_escapesInterpolatedNames() {
        // Employee names reach these templates from user input, and leave-requested puts one
        // inside <strong>. It was a <strong>{0}</strong> in messages.properties before sub-phase
        // 1.10, which would have forced th:utext here (ADR 0016).
        Map<String, Object> model = new LinkedHashMap<>(modelFor(EmailEvent.LEAVE_REQUESTED));
        model.put("requesterName", "<script>alert('xss')</script>");

        String html = asTenant(tenantA, () -> templates.render(EmailEvent.LEAVE_REQUESTED, model));

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void render_whenTheTenantHasNoLogo_fallsBackToItsName() {
        // The text fallback is what most tenants will show for a long time, so it is designed
        // rather than tolerated (BRAND.source.md §8.1) — and must not leave an empty <img>.
        String html = asTenant(tenantA, () -> templates.render(EmailEvent.INVITE, modelFor(EmailEvent.INVITE)));

        assertThat(html).doesNotContain("<img");
        assertThat(html).contains("Tenant A");
    }

    private static Map<String, Object> modelFor(EmailEvent event) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("Leave type", "Vacation");
        facts.put("Dates", "Aug 3, 2026 - Aug 10, 2026");
        facts.put("Working days", "5");

        return switch (event) {
            case INVITE -> Map.of("acceptUrl", "https://acme.localhost/accept-invite?token=abc");
            case PASSWORD_RESET -> Map.of("resetUrl", "https://acme.localhost/reset-password?token=abc");
            case LEAVE_REQUESTED ->
                    Map.of(
                            "requesterName", "Priya Nair",
                            "reviewUrl", "https://acme.localhost/for-action",
                            "facts", facts);
            case LEAVE_APPROVED, LEAVE_REJECTED ->
                    Map.of(
                            "leaveTypeName", "Vacation",
                            "dateRange", "Aug 3, 2026 - Aug 10, 2026",
                            "approverName", "Rahul Menon",
                            "decisionNote", "Team is short-staffed that week",
                            "facts", facts);
            case LEAVE_CANCELLED ->
                    Map.of("requesterName", "Priya Nair", "leaveTypeName", "Vacation", "facts", facts);
            case HOLIDAY_REMINDER ->
                    Map.of("holidayName", "Eid al-Adha", "holidayDate", "Friday, Aug 7");
            case BIRTHDAY -> Map.of("celebrantName", "Priya Nair");
            case WORK_ANNIVERSARY -> Map.of("celebrantName", "Priya Nair", "years", 3);
            case DOCUMENT_EXPIRY ->
                    Map.of(
                            "employeeName", "Priya Nair",
                            "documentName", "Passport",
                            "daysRemaining", 30,
                            "facts",
                                    Map.of(
                                            "Employee", "Priya Nair",
                                            "Document", "Passport (India)",
                                            "Expires", "Oct 15, 2026"));
        };
    }
}
