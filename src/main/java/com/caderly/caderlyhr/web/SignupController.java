package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.signup.AddressAvailability;
import com.caderly.caderlyhr.signup.PendingConfirmation;
import com.caderly.caderlyhr.signup.SignupRequest;
import com.caderly.caderlyhr.signup.SignupService;
import com.caderly.caderlyhr.signup.VerifyResult;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Self-serve sign-up pages (ADR 0022 decision C). Public by design; every method says so.
 *
 * <p>The base URLs handed to the service are built here because only the web layer knows the
 * request's scheme and port: the confirmation link points back at the public site, and the new
 * workspace's accept link at its own subdomain.
 */
@PublicSiteController
class SignupController {

    private static final Logger log = LoggerFactory.getLogger(SignupController.class);

    /** Region zones only (Asia/Dubai), not the legacy aliases (EST, SystemV/…) the JVM also lists. */
    private static final List<String> TIME_ZONES = regionTimeZones();

    private final SignupService signups;
    private final MessageSource messages;
    private final String baseDomain;

    SignupController(
            SignupService signups,
            MessageSource messages,
            @Value("${caderly.base-domain:localhost}") String baseDomain) {
        this.signups = signups;
        this.messages = messages;
        this.baseDomain = baseDomain;
    }

    @ModelAttribute("signupOpen")
    boolean signupOpen() {
        return signups.isOpen();
    }

    @ModelAttribute("baseDomain")
    String baseDomain() {
        return baseDomain;
    }

    @GetMapping("/signup")
    @PreAuthorize("permitAll()")
    String signupPage(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", SiteForms.Signup.blank());
        }
        addChoices(model);
        return "site/signup";
    }

    @PostMapping("/signup")
    @PreAuthorize("permitAll()")
    String signup(
            @Valid @ModelAttribute("form") SiteForms.Signup form,
            BindingResult binding,
            Model model,
            RedirectAttributes redirect) {
        if (form.isBot()) {
            // Look exactly like success, so a bot learns nothing from the response.
            log.info("Discarded a sign-up that filled the honeypot field");
            return "redirect:/signup/sent";
        }
        if (!binding.hasFieldErrors("timezone") && !TIME_ZONES.contains(form.timezone())) {
            binding.rejectValue("timezone", "invalid", message("validation.signup-form.timezone.required"));
        }
        if (binding.hasErrors()) {
            addChoices(model);
            return "site/signup";
        }
        try {
            signups.start(
                    new SignupRequest(
                            form.companyName(),
                            form.slug(),
                            form.timezone(),
                            Weekend.valueOf(form.weekend()).bitmask(),
                            form.adminName(),
                            form.adminEmail()),
                    siteBaseUrl());
        } catch (CaderlyException exception) {
            if (exception.errorCode().startsWith("SIGNUP_ADDRESS_")) {
                binding.rejectValue(
                        "slug",
                        exception.errorCode(),
                        WebMessages.errorDetail(messages, exception, LocaleContextHolder.getLocale()));
            } else {
                model.addAttribute(
                        "pageError", WebMessages.errorDetail(messages, exception, LocaleContextHolder.getLocale()));
            }
            addChoices(model);
            return "site/signup";
        }
        redirect.addFlashAttribute("sentTo", form.adminEmail().trim());
        redirect.addFlashAttribute("workspaceHost", signups.workspaceHost(form.slug()));
        return "redirect:/signup/sent";
    }

    @GetMapping("/signup/sent")
    @PreAuthorize("permitAll()")
    String sent() {
        return "site/signup-sent";
    }

    /**
     * Opens a confirmation link: shows what is about to be created and a button, nothing more. The
     * link is spent only by the POST below, so a mail scanner opening it first does no harm
     * (ADR 0022 decision C).
     */
    @GetMapping("/signup/verify")
    @PreAuthorize("permitAll()")
    String confirm(
            @RequestParam(name = "token", defaultValue = "") String token,
            Model model,
            HttpServletResponse response) {
        Optional<PendingConfirmation> pending = token.isBlank() ? Optional.empty() : signups.preview(token);
        if (pending.isEmpty()) {
            return linkInvalid(model, response);
        }
        model.addAttribute("token", token);
        model.addAttribute("pending", pending.get());
        return "site/signup-confirm";
    }

    /**
     * Spends a confirmation link. On success the browser lands on a same-site "ready" page rather
     * than being redirected to the new workspace: the CSP's {@code form-action 'self'} also covers
     * redirects after a form post, so a cross-origin redirect here would be blocked by the browser.
     * The set-password link that page shows is never logged.
     */
    @PostMapping("/signup/verify")
    @PreAuthorize("permitAll()")
    String verify(
            @RequestParam(name = "token", defaultValue = "") String token,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirect) {
        VerifyResult result =
                token.isBlank() ? new VerifyResult.LinkInvalid() : signups.verify(token, this::tenantBaseUrl);
        return switch (result) {
            case VerifyResult.Created created -> {
                redirect.addFlashAttribute("acceptUrl", created.acceptUrl());
                redirect.addFlashAttribute("workspaceHost", signups.workspaceHost(created.slug()));
                yield "redirect:/signup/created";
            }
            case VerifyResult.LinkInvalid invalid -> linkInvalid(model, response);
            case VerifyResult.AddressTaken taken -> {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                model.addAttribute("problem", "taken");
                model.addAttribute("workspaceHost", signups.workspaceHost(taken.slug()));
                yield "site/signup-link";
            }
        };
    }

    /**
     * The page after a successful confirmation. Its data arrives as flash attributes, so a reload or
     * a direct visit has nothing to show and goes to the sign-in page instead.
     */
    @GetMapping("/signup/created")
    @PreAuthorize("permitAll()")
    String created(Model model) {
        return model.containsAttribute("acceptUrl") ? "site/signup-created" : "redirect:/login";
    }

    private static String linkInvalid(Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_GONE);
        model.addAttribute("problem", "invalid");
        return "site/signup-link";
    }

    /** The address check behind the sign-up form's live status line (rate-limited, ADR 0022). */
    @PostMapping("/signup/check-address")
    @PreAuthorize("permitAll()")
    @ResponseBody
    AddressCheck checkAddress(@RequestParam(name = "slug", defaultValue = "") String slug) {
        AddressAvailability availability = signups.checkAddress(slug);
        String host = signups.workspaceHost(slug);
        return new AddressCheck(
                availability.isAvailable(),
                availability.name(),
                messages.getMessage(
                        availability.messageKey(), new Object[] {host}, LocaleContextHolder.getLocale()));
    }

    record AddressCheck(boolean available, String status, String message) {}

    private void addChoices(Model model) {
        model.addAttribute("timeZones", TIME_ZONES);
        model.addAttribute("weekends", Weekend.values());
    }

    private String message(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String siteBaseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    private String tenantBaseUrl(String slug) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .host(slug + "." + baseDomain)
                .build()
                .toUriString();
    }

    private static List<String> regionTimeZones() {
        Set<String> zones = new TreeSet<>();
        for (String zone : ZoneId.getAvailableZoneIds()) {
            if (zone.contains("/") && !zone.startsWith("Etc/") && !zone.startsWith("SystemV/")) {
                zones.add(zone);
            }
        }
        zones.add("UTC");
        return List.copyOf(zones);
    }
}
