package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the very first Super Admin account from env vars on startup.
 *
 * <p>Unlike {@code bootstrap.DevDataSeeder}, this runs in <em>every</em> environment, prod
 * included — deliberately not {@code @Profile("dev")} — because it is what makes the Super Admin
 * console reachable at all before any UI exists to create an account. It is idempotent (checked
 * by {@link SuperAdminRepository#count()}), so restarts do not fight it or reset a password an
 * operator has since changed.
 *
 * <p>Blank env vars are a deliberate no-op, not a startup failure. That inverts CLAUDE.md §6 A05's
 * usual "an unset secret must fail startup rather than silently use a guessed default" rule
 * (followed exactly by {@code caderly.people.encryption-key} and {@code caderly.email.from}), but
 * there is no safe default Super Admin password to fall back to, and every other environment —
 * CI, a fresh test profile, a pilot tenant that already provisioned its Super Admin by hand — must
 * be able to start with nothing configured here. So blank-vs-unset both mean "don't bootstrap,"
 * the one Super Admin config value allowed a blank default (see {@code application.yml}).
 */
@Component
class SuperAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final SuperAdminRepository superAdmins;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    SuperAdminBootstrap(
            SuperAdminRepository superAdmins,
            PasswordEncoder passwordEncoder,
            @Value("${caderly.superadmin.bootstrap-email:}") String bootstrapEmail,
            @Value("${caderly.superadmin.bootstrap-password:}") String bootstrapPassword) {
        this.superAdmins = superAdmins;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapEmail.isBlank() || bootstrapPassword.isBlank()) {
            log.info(
                    "Super Admin bootstrap: CADERLY_SUPERADMIN_EMAIL/PASSWORD not both set, skipping");
            return;
        }
        // Cross-tenant work by definition — there is no tenant to be "in" here — so it runs in
        // system mode, the same path SuperAdminDetailsService and TenantService#bySlug use.
        TenantContext.runAsSystem("bootstrap super admin", this::createIfAbsent);
    }

    /**
     * The table has at most one row ever, so a plain count-then-save is race-free enough for a
     * once-per-deployment startup task; a concurrent-startup unique-constraint retry would be
     * over-engineering for something that runs once per process.
     */
    private Void createIfAbsent() {
        if (superAdmins.count() > 0) {
            return null;
        }
        superAdmins.save(new SuperAdmin(bootstrapEmail, passwordEncoder.encode(bootstrapPassword)));
        // Never log the password.
        log.info("Super Admin bootstrap: created Super Admin account for {}", bootstrapEmail);
        return null;
    }
}
