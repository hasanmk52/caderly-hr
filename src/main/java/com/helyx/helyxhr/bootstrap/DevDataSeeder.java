package com.helyx.helyxhr.bootstrap;

import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds a tenant and one Admin so a fresh dev database is usable immediately.
 *
 * <p>Exists only because nothing else can create the first user yet: Super Admin owns tenant and
 * initial-Admin creation (PRD FR-1.8) and does not arrive until Phase 1.13. Delete this class
 * then.
 *
 * <p>{@code @Profile("dev")} keeps it out of every other environment, and it is idempotent, so
 * restarts do not fight it or reset a password you have changed by hand.
 */
@Component
@Profile("dev")
class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;
    private final String tenantSlug;
    private final String tenantName;
    private final String adminEmail;
    private final String adminPassword;

    DevDataSeeder(
            TenantRepository tenants,
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactions,
            @Value("${helyx.dev.tenant-slug:mhz}") String tenantSlug,
            @Value("${helyx.dev.tenant-name:MHZ Software}") String tenantName,
            @Value("${helyx.dev.admin-email:admin@mhz.test}") String adminEmail,
            @Value("${helyx.dev.admin-password:DevAdmin12345}") String adminPassword) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.tenantSlug = tenantSlug;
        this.tenantName = tenantName;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = seedTenant();
        seedAdmin(tenantId);
    }

    /**
     * Creating a tenant is cross-tenant work by definition — there is no tenant to be "in" yet —
     * so it runs in system mode, the same path {@code TenantService#bySlug} uses.
     */
    private UUID seedTenant() {
        return TenantContext.runAsSystem(
                "dev bootstrap: seed tenant",
                () ->
                        transactions.execute(
                                status ->
                                        tenants
                                                .findBySlugAndDeletedAtIsNull(tenantSlug)
                                                .orElseGet(
                                                        () -> {
                                                            log.info("Dev bootstrap: creating tenant '{}'", tenantSlug);
                                                            return tenants.save(new Tenant(tenantSlug, tenantName));
                                                        })
                                                .getId()));
    }

    /** The admin itself is ordinary tenant-scoped data, so this runs with the tenant active. */
    private void seedAdmin(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            transactions.executeWithoutResult(
                    status -> {
                        if (users.existsByEmail(adminEmail)) {
                            return;
                        }
                        AppUser admin = AppUser.active(adminEmail, passwordEncoder.encode(adminPassword));
                        admin.grant(Role.ADMIN);
                        users.save(admin);
                        // The password is a known dev default, not a secret, and printing it is the
                        // point — but it is still worth being explicit that this only ever runs
                        // under the dev profile.
                        log.info(
                                "Dev bootstrap: created admin {} — sign in at http://{}.localhost:8080/login",
                                adminEmail,
                                tenantSlug);
                    });
        } finally {
            TenantContext.clear();
        }
    }
}
