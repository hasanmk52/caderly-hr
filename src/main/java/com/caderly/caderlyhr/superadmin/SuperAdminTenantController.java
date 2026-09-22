package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.identity.ImpersonationService;
import com.caderly.caderlyhr.identity.ImpersonationService.AdminAccount;
import com.caderly.caderlyhr.people.PeopleFacade;
import com.caderly.caderlyhr.security.SecurityPaths;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import com.caderly.caderlyhr.tenant.TenantFacade.TenantAdminView;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The Super Admin console's tenant list (PRD FR-1.8): create, suspend/reinstate, soft-delete, and
 * impersonate — the operator's entire cross-tenant toolkit for this phase.
 *
 * <p>{@code @PreAuthorize} is repeated on every method as well as on the class (ArchitectureTest
 * requires it, same convention as {@code web.AdminAuditController}). No {@code @Transactional}
 * here (CLAUDE.md §7) — every write goes through {@link TenantFacade}, {@link
 * TenantProvisioningService}, or {@link ImpersonationService}, each of which owns its own
 * transaction boundary; see {@code superadmin}'s package Javadoc for why none of that can be a
 * shared, controller-level transaction (this realm's threads never carry a resolved {@code
 * TenantContext} the way a tenant request would).
 *
 * <p>Every list/count read here runs under either {@link TenantFacade#listAllForAdmin()}'s own
 * {@code runAsSystem} (the cross-tenant {@code tenant} table has no {@code tenant_id} to scope
 * by) or, for the per-tenant employee count, a real resolved {@link TenantContext} set one tenant
 * at a time — {@code employee} is RLS-protected, so {@code runAsSystem} alone would read zero rows
 * (ADR 0003). The loop below mirrors {@code people.EmployeeTerminationJob}'s serial fan-out
 * exactly: set, read, clear, one tenant at a time, never batched or parallelized (Global
 * Constraint 9 — this scales to a handful of pilot tenants, not thousands).
 */
@Controller
@PreAuthorize("hasRole('SUPER_ADMIN')")
class SuperAdminTenantController {

    private final TenantFacade tenants;
    private final TenantProvisioningService provisioning;
    private final PeopleFacade people;
    private final ImpersonationService impersonation;
    private final MessageSource messages;
    private final String baseDomain;

    SuperAdminTenantController(
            TenantFacade tenants,
            TenantProvisioningService provisioning,
            PeopleFacade people,
            ImpersonationService impersonation,
            MessageSource messages,
            @Value("${caderly.base-domain:localhost}") String baseDomain) {
        this.tenants = tenants;
        this.provisioning = provisioning;
        this.people = people;
        this.impersonation = impersonation;
        this.messages = messages;
        this.baseDomain = baseDomain;
    }

    @GetMapping("/superadmin/tenants")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String list(Model model) {
        model.addAttribute("tenantForm", blankForm());
        populateTable(model);
        return "superadmin/tenants";
    }

    /**
     * Creates a tenant and invites its first Admin. A duplicate slug or another validation failure
     * re-renders the same page with the error attached to the {@code slug} field, following {@code
     * web.AdminOrganizationController}'s create-endpoint pattern for a {@link CaderlyException}
     * (this console has no {@code web.WebMessages} to reuse — it is package-private in a different
     * package — so the same "{@code error.} + errorCode" key scheme is re-applied inline via
     * {@link #errorDetail}).
     */
    @PostMapping("/superadmin/tenants")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String create(@Valid @ModelAttribute("tenantForm") TenantCreateForm form, BindingResult binding, Model model) {
        if (!binding.hasErrors()) {
            try {
                provisioning.provision(
                        form.slug(),
                        form.name(),
                        form.timezone(),
                        form.weekendDays(),
                        form.logoUrl(),
                        form.firstAdminEmail(),
                        baseUrl());
                return "redirect:/superadmin/tenants";
            } catch (CaderlyException exception) {
                binding.rejectValue("slug", exception.errorCode(), errorDetail(exception));
            }
        }
        populateTable(model);
        model.addAttribute("openCreatePanel", true);
        return "superadmin/tenants";
    }

    /**
     * Toggles suspension. {@code HX-Redirect} makes htmx do a full client-side navigation back to
     * the list rather than trying to swap a fragment in place (there is nothing sensible to swap a
     * PATCH's own response into) — the plain {@code "redirect:"} view alongside it is what a
     * non-htmx caller (this endpoint's own MockMvc tests included) actually receives.
     */
    @PatchMapping("/superadmin/tenants/{id}/suspend")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String toggleSuspend(@PathVariable UUID id, HttpServletResponse response) {
        TenantAdminView tenant = requireTenant(id);
        if (tenant.suspended()) {
            tenants.reinstate(id);
        } else {
            tenants.suspend(id);
        }
        return redirectToList(response);
    }

    @DeleteMapping("/superadmin/tenants/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String delete(@PathVariable UUID id, HttpServletResponse response) {
        tenants.softDelete(id);
        return redirectToList(response);
    }

    /**
     * Mints an impersonation ticket and sends the browser to the tenant's own subdomain to redeem
     * it. A plain, non-htmx {@code <form method="post">} on purpose (see {@code
     * superadmin/tenants.html}): the destination is a different host than this console, and only a
     * real top-level browser navigation follows a cross-origin redirect the way this needs to —
     * an htmx AJAX call to it would be a cross-origin XHR with no CORS policy allowing it.
     *
     * <p>No Admin currently ACTIVE in the tenant is not an error the operator caused, so it is
     * reported the same way a bad or expired ticket is on the tenant side: a redirect back with a
     * query-flag alert, minting nothing (PRD FR-1.8's "no admin-picker UI" MVP simplification — see
     * {@link ImpersonationService#findAnyAdmin}).
     */
    @PostMapping("/superadmin/tenants/{id}/impersonate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String impersonate(@PathVariable UUID id) {
        TenantAdminView tenant = requireTenant(id);
        Optional<AdminAccount> admin = impersonation.findAnyAdmin(id);
        if (admin.isEmpty()) {
            return "redirect:/superadmin/tenants?impersonateFailed";
        }

        SuperAdminPrincipal current = currentSuperAdmin();
        String token =
                impersonation.mint(current.superAdminId(), current.getUsername(), id, admin.get().userId());

        return "redirect:https://" + tenant.slug() + "." + baseDomain + SecurityPaths.IMPERSONATE_PATH + "?token=" + token;
    }

    private String redirectToList(HttpServletResponse response) {
        // See toggleSuspend's Javadoc: this header is what makes an htmx-issued PATCH/DELETE end
        // in a full navigation instead of an in-place fragment swap.
        response.setHeader("HX-Redirect", "/superadmin/tenants");
        return "redirect:/superadmin/tenants";
    }

    private void populateTable(Model model) {
        List<TenantAdminView> all = tenants.listAllForAdmin();
        model.addAttribute("tenantRows", all.stream().map(this::toRow).toList());
    }

    /**
     * Employee count is read one tenant at a time under a real, resolved {@code TenantContext} —
     * never {@code runAsSystem} — because {@code employee} is RLS-protected (see this class's
     * Javadoc). {@code TenantContext.clear()} runs in a {@code finally} so a failure counting one
     * tenant's employees can never leave the next row reading under the wrong tenant.
     */
    private TenantRow toRow(TenantAdminView tenant) {
        TenantContext.set(tenant.id());
        long employeeCount;
        try {
            employeeCount = people.countActiveEmployees();
        } finally {
            TenantContext.clear();
        }
        String status = tenant.deletedAt() != null ? "DELETED" : tenant.suspended() ? "SUSPENDED" : "ACTIVE";
        return new TenantRow(tenant.id(), tenant.slug(), tenant.name(), status, employeeCount);
    }

    private TenantAdminView requireTenant(UUID id) {
        return tenants.find(id).orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "Tenant not found"));
    }

    private static SuperAdminPrincipal currentSuperAdmin() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof SuperAdminPrincipal superAdmin)) {
            // Unreachable through the security chain: this method is @PreAuthorize("hasRole('SUPER_ADMIN')")
            // on a realm whose only authenticated principal type is SuperAdminPrincipal.
            throw new IllegalStateException("Authenticated principal is not a Super Admin");
        }
        return superAdmin;
    }

    /** Mirrors {@code web.RequestTenant#baseUrl} (package-private in a different package). */
    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    /** Mirrors {@code web.WebMessages#errorDetail} (package-private in a different package). */
    private String errorDetail(CaderlyException exception) {
        String key = "error." + exception.errorCode().toLowerCase(Locale.ROOT).replace('_', '-');
        return messages.getMessage(key, null, exception.getMessage(), LocaleContextHolder.getLocale());
    }

    private static TenantCreateForm blankForm() {
        return new TenantCreateForm("", "", "UTC", 96, null, "");
    }

    record TenantRow(UUID id, String slug, String name, String status, long employeeCount) {}

    record TenantCreateForm(
            @NotBlank(message = "{validation.tenant-create-form.slug.required}")
                    @Size(max = 50, message = "{validation.tenant-create-form.slug.too-long}")
                    @Pattern(
                            regexp = "^[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?$",
                            message = "{validation.tenant-create-form.slug.invalid}")
                    String slug,
            @NotBlank(message = "{validation.tenant-create-form.name.required}")
                    @Size(max = 200, message = "{validation.tenant-create-form.name.too-long}")
                    String name,
            @NotBlank(message = "{validation.tenant-create-form.timezone.required}")
                    @Size(max = 50, message = "{validation.tenant-create-form.timezone.too-long}")
                    String timezone,
            @Min(value = 0, message = "{validation.tenant-create-form.weekend-days.invalid}")
                    @Max(value = 127, message = "{validation.tenant-create-form.weekend-days.invalid}")
                    int weekendDays,
            @Nullable @Size(max = 500, message = "{validation.tenant-create-form.logo-url.too-long}") String logoUrl,
            @NotBlank(message = "{validation.tenant-create-form.first-admin-email.required}")
                    @Email(message = "{validation.tenant-create-form.first-admin-email.invalid}")
                    String firstAdminEmail) {}
}
