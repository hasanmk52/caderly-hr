package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.notifications.NotificationAdminService;
import com.caderly.caderlyhr.notifications.system.EmailStatus;
import com.caderly.caderlyhr.tenant.TenantFacade;
import com.caderly.caderlyhr.tenant.TenantFacade.NotificationSettings;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin's view of the email outbox and the per-tenant notification categories (PRD FR-9.2/9.3,
 * §24.9's Notifications entry; §26: "Configure tenant settings" is Admin-only).
 *
 * <p>{@code @PreAuthorize} is repeated on every method as well as on the class, which
 * {@code ArchitectureTest} requires: an inherited grant is easy to lose by moving a method.
 *
 * <p>No {@code @Transactional} here (CLAUDE.md §7) — and no tenant handling either. Both
 * {@link NotificationAdminService} calls read {@code TenantContext} themselves precisely so this
 * layer cannot get it wrong; {@code email_outbox} has no RLS to catch it if it did.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
class AdminNotificationsController {

    private final NotificationAdminService notifications;
    private final TenantFacade tenants;
    private final MessageSource messages;

    AdminNotificationsController(
            NotificationAdminService notifications, TenantFacade tenants, MessageSource messages) {
        this.notifications = notifications;
        this.tenants = tenants;
        this.messages = messages;
    }

    @GetMapping("/admin/notifications")
    @PreAuthorize("hasRole('ADMIN')")
    String page(
            @RequestParam(required = false) @Nullable EmailStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        addSettings(model);
        addLog(model, status, from, to, page);
        return "admin/notifications";
    }

    @PostMapping("/admin/notifications/settings")
    @PreAuthorize("hasRole('ADMIN')")
    String saveSettings(
            @RequestParam(defaultValue = "false") boolean holidayReminder,
            @RequestParam(defaultValue = "false") boolean documentExpiry,
            @RequestParam(defaultValue = "false") boolean birthday,
            @RequestParam(defaultValue = "false") boolean workAnniversary,
            Model model,
            HttpServletResponse response) {
        // Unchecked switches send no parameter at all, which is why every flag defaults to false
        // rather than being read as "unchanged".
        tenants.updateNotificationSettings(
                new NotificationSettings(holidayReminder, documentExpiry, birthday, workAnniversary));
        toast(response, "toast.notification.settings-saved", "Notification settings saved");
        addSettings(model);
        return "admin/notifications :: settings";
    }

    @PostMapping("/admin/notifications/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    String retry(
            @PathVariable UUID id,
            @RequestParam(required = false) @Nullable EmailStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            Model model,
            HttpServletResponse response) {
        notifications.requeue(id);
        toast(response, "toast.notification.retried", "Email queued for another attempt");
        // Re-render with the same filters the Admin was looking at, so the row they just retried
        // does not vanish into an unfiltered list.
        addLog(model, status, from, to, page);
        return "admin/notifications :: content";
    }

    private void addSettings(Model model) {
        model.addAttribute("settings", tenants.currentNotificationSettings());
    }

    private void addLog(
            Model model,
            @Nullable EmailStatus status,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            int page) {
        var rows = notifications.list(status, from, to, page);
        model.addAttribute("rows", rows.getContent());
        model.addAttribute("pageNumber", rows.getNumber());
        model.addAttribute("totalPages", Math.max(rows.getTotalPages(), 1));
        model.addAttribute("hasPrevious", rows.hasPrevious());
        model.addAttribute("hasNext", rows.hasNext());
        model.addAttribute("statusOptions", List.of(EmailStatus.values()));
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedFrom", from);
        model.addAttribute("selectedTo", to);
    }

    /** Same HX-Trigger toast mechanism the other admin controllers use (UI Guidelines §7.4). */
    private void toast(HttpServletResponse response, String key, String defaultMessage) {
        String message =
                messages.getMessage(key, null, defaultMessage, LocaleContextHolder.getLocale());
        response.setHeader("HX-Trigger", "{\"organization-toast\": {\"message\": \"" + message + "\"}}");
    }
}
