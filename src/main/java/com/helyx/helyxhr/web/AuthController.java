package com.helyx.helyxhr.web;

import com.helyx.helyxhr.common.HelyxException;
import com.helyx.helyxhr.identity.InviteService;
import com.helyx.helyxhr.identity.PasswordResetService;
import com.helyx.helyxhr.tenant.TenantSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.ui.Model;

/**
 * The four unauthenticated pages: login, forgot password, reset password, and invite acceptance.
 *
 * <p>Every method carries {@code @PreAuthorize("permitAll()")} rather than relying on the URL
 * rules in {@code SecurityConfig}. It is redundant by design — CLAUDE.md §6 A01 requires an
 * explicit annotation on every controller method, and an ArchUnit rule enforces it, so "public"
 * is stated rather than inferred from a path list somewhere else.
 */
@Controller
class AuthController {

    private final InviteService invites;
    private final PasswordResetService resets;

    AuthController(InviteService invites, PasswordResetService resets) {
        this.invites = invites;
        this.resets = resets;
    }

    @GetMapping("/login")
    @PreAuthorize("permitAll()")
    String loginPage() {
        return "auth/login";
    }

    @GetMapping("/forgot-password")
    @PreAuthorize("permitAll()")
    String forgotPasswordPage(Model model) {
        model.addAttribute("form", new AuthForms.ForgotPassword(""));
        return "auth/forgot-password";
    }

    /**
     * Always reports success, whether or not the address exists (ADR 0006 decision E). The only
     * branch here is on the form being well-formed.
     */
    @PostMapping("/forgot-password")
    @PreAuthorize("permitAll()")
    String requestPasswordReset(
            @Valid @ModelAttribute("form") AuthForms.ForgotPassword form,
            BindingResult binding,
            HttpServletRequest request,
            Model model) {

        if (binding.hasErrors()) {
            return "auth/forgot-password";
        }

        TenantSummary tenant = RequestTenant.of(request);
        resets.requestReset(form.email(), RequestTenant.baseUrl(), tenant.name());

        model.addAttribute("submitted", true);
        return "auth/forgot-password";
    }

    @GetMapping("/reset-password")
    @PreAuthorize("permitAll()")
    String resetPasswordPage(@RequestParam("token") String token, Model model) {
        // Checked up front so a dead link says so immediately, rather than after the user has
        // typed and confirmed a new password.
        model.addAttribute("tokenValid", resets.isTokenRedeemable(token));
        model.addAttribute("form", new AuthForms.SetPassword(token, "", ""));
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    @PreAuthorize("permitAll()")
    String completePasswordReset(
            @Valid @ModelAttribute("form") AuthForms.SetPassword form,
            BindingResult binding,
            Model model) {

        rejectMismatchedConfirmation(form, binding);
        model.addAttribute("tokenValid", true);
        if (binding.hasErrors()) {
            return "auth/reset-password";
        }

        try {
            resets.completeReset(form.token(), form.password());
        } catch (HelyxException exception) {
            model.addAttribute("tokenValid", false);
            model.addAttribute("pageError", exception.getMessage());
            return "auth/reset-password";
        }
        return "redirect:/login?resetComplete";
    }

    @GetMapping("/accept-invite")
    @PreAuthorize("permitAll()")
    String acceptInvitePage(@RequestParam("token") String token, Model model) {
        model.addAttribute("form", new AuthForms.SetPassword(token, "", ""));
        return "auth/accept-invite";
    }

    @PostMapping("/accept-invite")
    @PreAuthorize("permitAll()")
    String acceptInvite(
            @Valid @ModelAttribute("form") AuthForms.SetPassword form,
            BindingResult binding,
            Model model) {

        rejectMismatchedConfirmation(form, binding);
        if (binding.hasErrors()) {
            return "auth/accept-invite";
        }

        try {
            invites.acceptInvite(form.token(), form.password());
        } catch (HelyxException exception) {
            model.addAttribute("pageError", exception.getMessage());
            return "auth/accept-invite";
        }
        return "redirect:/login?inviteAccepted";
    }

    /** Attaches the mismatch to the confirmation field so the UI can mark that input. */
    private static void rejectMismatchedConfirmation(
            AuthForms.SetPassword form, BindingResult binding) {
        if (!binding.hasFieldErrors("password") && !form.passwordsMatch()) {
            binding.addError(
                    new FieldError(
                            "form", "confirmPassword", "Passwords do not match"));
        }
    }
}
