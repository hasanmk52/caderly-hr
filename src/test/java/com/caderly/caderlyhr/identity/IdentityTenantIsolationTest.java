package com.caderly.caderlyhr.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CLAUDE.md §5 rule 8: every new tenant-scoped entity gets a test proving cross-tenant reads come
 * back empty. Covers all three identity entities added in sub-phase 1.2.
 */
class IdentityTenantIsolationTest extends TenantIsolationTestBase {

    @Autowired private AppUserRepository users;
    @Autowired private PasswordResetTokenRepository resetTokens;
    @Autowired private PostgreSQLContainer postgres;

    private String emailA;
    private String emailB;

    @BeforeEach
    void seedUsers() {
        emailA = "a-" + UUID.randomUUID() + "@example.test";
        emailB = "b-" + UUID.randomUUID() + "@example.test";
        asTenant(tenantA, () -> users.save(AppUser.active(emailA, "hash-a")));
        asTenant(tenantB, () -> users.save(AppUser.active(emailB, "hash-b")));
    }

    @Test
    void findAll_whenTenantAActive_returnsOnlyTenantAUsers() {
        List<AppUser> visible = asTenant(tenantA, () -> users.findAll());

        assertThat(visible).isNotEmpty();
        assertThat(visible).allSatisfy(u -> assertThat(u.getTenantId()).isEqualTo(tenantA));
        assertThat(visible).extracting(AppUser::email).contains(emailA).doesNotContain(emailB);
    }

    @Test
    void save_whenTenantAActive_assignsTenantIdAutomatically() {
        AppUser saved =
                asTenant(tenantA, () -> users.save(AppUser.active("auto@example.test", "hash")));

        assertThat(saved.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void findByEmail_whenUserBelongsToOtherTenant_returnsEmpty() {
        // The mechanism the whole login story rests on (ADR 0006 decision A): the same lookup
        // that authenticates a user is what makes a foreign tenant's account invisible, so
        // "valid email, wrong subdomain" can only ever surface as user-not-found.
        Optional<AppUser> crossTenant = asTenant(tenantB, () -> users.findByEmail(emailA));

        assertThat(crossTenant).isEmpty();
        assertThat(asTenant(tenantA, () -> users.findByEmail(emailA))).isPresent();
    }

    @Test
    void findById_whenTenantBActiveOnTenantARow_returnsEmpty() {
        UUID tenantAUserId = asTenant(tenantA, () -> users.findByEmail(emailA)).orElseThrow().getId();

        assertThat(asTenant(tenantB, () -> users.findById(tenantAUserId))).isEmpty();
    }

    @Test
    void existsByEmail_whenUserBelongsToOtherTenant_returnsFalse() {
        // BR-12: the same address may legitimately exist in two tenants, so the uniqueness
        // probe used by the invite flow must not see across the boundary either.
        assertThat(asTenant(tenantB, () -> users.existsByEmail(emailA))).isFalse();
        assertThat(asTenant(tenantA, () -> users.existsByEmail(emailA))).isTrue();
    }

    @Test
    void roles_whenGrantedInTenantA_areInvisibleToTenantB() {
        asTenant(
                tenantA,
                () -> {
                    AppUser user = users.findByEmail(emailA).orElseThrow();
                    user.grant(Role.ADMIN);
                    return users.save(user);
                });

        assertThat(asTenant(tenantA, () -> users.findByEmail(emailA).orElseThrow().roles()))
                .containsExactly(Role.ADMIN);
        assertThat(asTenant(tenantB, () -> users.findByEmail(emailA))).isEmpty();
    }

    @Test
    void findByTokenHash_whenTokenBelongsToOtherTenant_returnsEmpty() {
        String tokenHash = "hash-" + UUID.randomUUID();
        asTenant(
                tenantA,
                () -> {
                    AppUser user = users.findByEmail(emailA).orElseThrow();
                    return resetTokens.save(
                            new PasswordResetToken(
                                    user, tokenHash, Instant.now().plus(1, ChronoUnit.HOURS)));
                });

        assertThat(asTenant(tenantB, () -> resetTokens.findByTokenHash(tokenHash))).isEmpty();
        assertThat(asTenant(tenantA, () -> resetTokens.findByTokenHash(tokenHash))).isPresent();
    }

    @Test
    void rawJdbc_withTenantASetting_rlsReturnsOnlyTenantAUsers() throws Exception {
        // Independent of Hibernate: proves the Postgres policy holds even if the ORM
        // restriction were bypassed. Uses the non-superuser rls_probe role.
        assertThat(selectEmailsAsRestrictedRole(tenantA)).contains(emailA).doesNotContain(emailB);
    }

    @Test
    void rawJdbc_withoutTenantSetting_rlsReturnsNoUsers() throws Exception {
        assertThat(selectEmailsAsRestrictedRole(null)).isEmpty();
    }

    private List<String> selectEmailsAsRestrictedRole(UUID tenantId) throws Exception {
        List<String> emails = new ArrayList<>();
        try (Connection connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), "rls_probe", "rls_probe")) {
            connection.setAutoCommit(false);
            try {
                if (tenantId != null) {
                    try (PreparedStatement ps =
                            connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                        ps.setString(1, tenantId.toString());
                        ps.execute();
                    }
                }
                try (PreparedStatement ps =
                                connection.prepareStatement("SELECT email FROM caderly_hr.app_user");
                        ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        emails.add(rs.getString(1));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        return emails;
    }
}
