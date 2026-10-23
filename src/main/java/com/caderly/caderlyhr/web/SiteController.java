package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.signup.SignupService;
import com.caderly.caderlyhr.tenant.SlugPolicy;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The public site's read-only pages on the base domain (ADR 0022): home, the legal documents, and
 * the "find your workspace" login page. Everything here is public, stated per method as CLAUDE.md
 * §6 A01 requires.
 */
@PublicSiteController
class SiteController {

    private static final Pattern SLUG_FORMAT = Pattern.compile(SlugPolicy.FORMAT_REGEX);

    private final SignupService signups;
    private final String baseDomain;

    SiteController(SignupService signups, @Value("${caderly.base-domain:localhost}") String baseDomain) {
        this.signups = signups;
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

    @GetMapping("/")
    @PreAuthorize("permitAll()")
    String home() {
        return "site/home";
    }

    @GetMapping("/terms")
    @PreAuthorize("permitAll()")
    String terms() {
        return "site/legal/terms";
    }

    @GetMapping("/privacy")
    @PreAuthorize("permitAll()")
    String privacy() {
        return "site/legal/privacy";
    }

    @GetMapping("/dpa")
    @PreAuthorize("permitAll()")
    String dpa() {
        return "site/legal/dpa";
    }

    @GetMapping("/login")
    @PreAuthorize("permitAll()")
    String login() {
        return "site/login";
    }

    /**
     * Offers a link to a workspace's own sign-in page. It is a link rather than a redirect because
     * the CSP's {@code form-action 'self'} blocks a cross-origin redirect after a form submit
     * (ADR 0022 decision C). Never checks whether the workspace exists — an unknown one lands on
     * the tenant 404 page, so this can't be used to list customers (ADR 0022 decision H). The
     * target host is built only from a format-valid slug and the configured base domain, so it
     * can't be turned into an open redirect.
     */
    @GetMapping("/login/go")
    @PreAuthorize("permitAll()")
    String goToWorkspace(@RequestParam(name = "workspace", defaultValue = "") String workspace, Model model) {
        String slug = stripBaseDomain(SlugPolicy.normalise(workspace));
        if (!SLUG_FORMAT.matcher(slug).matches() || SlugPolicy.isReserved(slug)) {
            model.addAttribute("workspace", workspace);
            model.addAttribute("invalidWorkspace", true);
            return "site/login";
        }
        String host = slug + "." + baseDomain;
        String target =
                ServletUriComponentsBuilder.fromCurrentContextPath()
                        .host(host)
                        .replacePath("/login")
                        .replaceQuery(null)
                        .build()
                        .toUriString();
        model.addAttribute("workspaceHost", host);
        model.addAttribute("workspaceLoginUrl", target);
        return "site/login-continue";
    }

    /** People paste the whole address; accept {@code acme.caderly.app} as well as {@code acme}. */
    private String stripBaseDomain(String value) {
        String suffix = "." + baseDomain;
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }
}
