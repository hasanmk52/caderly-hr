package com.helyx.helyxhr.tenantisolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.postgresql.PostgreSQLContainer;

class TenantIsolationTest extends TenantIsolationTestBase {

    @Autowired private IsolationProbeService probes;
    @Autowired private PostgreSQLContainer postgres;

    private String labelA;
    private String labelB;

    @BeforeEach
    void seedProbes() {
        labelA = "probe-a-" + UUID.randomUUID();
        labelB = "probe-b-" + UUID.randomUUID();
        asTenant(tenantA, () -> probes.save(labelA));
        asTenant(tenantB, () -> probes.save(labelB));
    }

    @Test
    void findAll_whenTenantAActive_returnsOnlyTenantARows() {
        List<IsolationProbe> visible = asTenant(tenantA, probes::findAll);

        assertThat(visible).isNotEmpty();
        assertThat(visible).allSatisfy(p -> assertThat(p.getTenantId()).isEqualTo(tenantA));
        assertThat(visible).extracting(IsolationProbe::label).contains(labelA).doesNotContain(labelB);
    }

    @Test
    void save_whenTenantAActive_assignsTenantIdAutomatically() {
        IsolationProbe saved = asTenant(tenantA, () -> probes.save("auto-" + UUID.randomUUID()));

        assertThat(saved.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void whenNoTenantContext_anyOperationThrows() {
        // Hibernate's @TenantId resolver (TenantIdentifierResolver, ADR 0004) throws while
        // opening the EntityManager for the transaction, before the repository call itself
        // ever runs — Spring surfaces that as CannotCreateTransactionException wrapping the
        // original IllegalStateException. This happens identically for reads and writes (the
        // failure is at transaction-begin, not inside save/findAll's own logic), so one
        // representative call is enough to prove the contract.
        assertThatThrownBy(() -> probes.save("orphan"))
                .isInstanceOf(CannotCreateTransactionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void findAll_inSystemMode_seesNoTenantsRows() {
        // Unlike the old hand-written @Filter (opt-in per query), Hibernate's native
        // @TenantId restriction is armed automatically for every session, including
        // system-mode ones — TenantIdentifierResolver gives system mode a fixed sentinel
        // no real tenant can ever have (ADR 0004), so this now returns zero rows exactly
        // like the RLS backstop already does in real dev/prod (ADR 0003) — system mode
        // was never actually able to see cross-tenant data outside this test's previous
        // Testcontainers-superuser artifact. Real cross-tenant reads remain deferred to
        // the Super Admin console (ADR 0003).
        List<IsolationProbe> visible =
                TenantContext.runAsSystem("isolation test: unscoped read", probes::findAll);

        assertThat(visible).isEmpty();
    }

    @Test
    void findById_whenTenantBActiveOnTenantARow_returnsEmpty() {
        // The regression test for ADR 0004's actual improvement over the old hand-written
        // @Filter: @Filter never applied to EntityManager.find(id)/repository.findById (only
        // to HQL/Criteria queries), so a direct by-id load could leak across tenants unless
        // RLS caught it. Hibernate's native @TenantId restriction is armed for every query
        // it generates, including findById, so this must return empty even without RLS's
        // help (the Testcontainers datasource user is a superuser and bypasses RLS).
        UUID tenantARowId =
                asTenant(tenantA, () -> probes.save("findById-target-" + UUID.randomUUID())).getId();

        Optional<IsolationProbe> visibleToTenantB =
                asTenant(tenantB, () -> probes.findById(tenantARowId));

        assertThat(visibleToTenantB).isEmpty();
    }

    @Test
    void relabel_whenTenantBAttemptsTenantARow_seesNothingAndLeavesRowUnchanged() {
        UUID tenantARowId = asTenant(tenantA, () -> probes.save("original")).getId();

        Optional<IsolationProbe> result =
                asTenant(tenantB, () -> probes.relabel(tenantARowId, "hijacked"));

        assertThat(result).isEmpty();
        assertThat(asTenant(tenantA, () -> probes.findById(tenantARowId)))
                .isPresent()
                .get()
                .extracting(IsolationProbe::label)
                .isEqualTo("original");
    }

    @Test
    void deleteById_whenTenantBAttemptsTenantARow_doesNotDeleteIt() {
        // The direct-delete-by-id path is worth checking on its own: it never goes through
        // findById first, so it's a separate proof that @TenantId scopes it too, not just
        // entity-loading paths (mirrors the ADR 0004 find(id) regression test above).
        UUID tenantARowId = asTenant(tenantA, () -> probes.save("must-survive")).getId();

        asTenant(tenantB, () -> probes.deleteById(tenantARowId));

        assertThat(asTenant(tenantA, () -> probes.existsById(tenantARowId))).isTrue();
    }

    @Test
    void endToEnd_adminOnboardsTenantThenTenantDoesFullCrudSeamlessly() {
        // Mirrors the real lifecycle this whole mechanism exists for: a system/admin
        // operation creates a brand-new tenant (no Super Admin console yet - Phase 1.13 -
        // so this uses the same runAsSystem path TenantService#bySlug and the test fixtures
        // already prove works), then a normal request against that tenant's subdomain does
        // full create/read/update/delete on a TenantAwareEntity with zero entity-specific
        // tenant code, and none of it is visible to a different, pre-existing tenant.
        UUID freshTenantId =
                TenantContext.runAsSystem(
                        "test: admin onboards a new tenant",
                        () ->
                                tenantRepository
                                        .save(new Tenant("fresh-" + UUID.randomUUID(), "Fresh Co"))
                                        .getId());

        UUID rowId = asTenant(freshTenantId, () -> probes.save("day-one")).getId();
        assertThat(asTenant(freshTenantId, () -> probes.findById(rowId)))
                .isPresent()
                .get()
                .extracting(IsolationProbe::label)
                .isEqualTo("day-one");

        asTenant(freshTenantId, () -> probes.relabel(rowId, "day-two"));
        assertThat(asTenant(freshTenantId, () -> probes.findById(rowId)))
                .isPresent()
                .get()
                .extracting(IsolationProbe::label)
                .isEqualTo("day-two");

        // An older, unrelated tenant (tenantA, seeded in @BeforeEach) never sees this row.
        assertThat(asTenant(tenantA, () -> probes.existsById(rowId))).isFalse();

        asTenant(freshTenantId, () -> probes.deleteById(rowId));
        assertThat(asTenant(freshTenantId, () -> probes.existsById(rowId))).isFalse();
    }

    @Test
    void rawJdbc_withTenantASetting_rlsReturnsOnlyTenantARows() throws Exception {
        // No Hibernate involved, non-superuser role: proves the Postgres RLS policy is an
        // independent backstop even if the ORM filter were disabled or bypassed.
        List<String> labels = selectLabelsAsRestrictedRole(tenantA);

        assertThat(labels).contains(labelA).doesNotContain(labelB);
    }

    @Test
    void rawJdbc_withoutTenantSetting_rlsReturnsNoRows() throws Exception {
        // current_setting('app.tenant_id', true) is NULL when unset -> policy denies all.
        List<String> labels = selectLabelsAsRestrictedRole(null);

        assertThat(labels).isEmpty();
    }

    private List<String> selectLabelsAsRestrictedRole(UUID tenantId) throws Exception {
        List<String> labels = new ArrayList<>();
        // rls_probe (created by the test migration) is deliberately NOT the superuser the
        // app datasource uses in tests — superusers bypass RLS, ordinary roles don't.
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
                             connection.prepareStatement("SELECT label FROM helyx_hr.isolation_probe");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        labels.add(rs.getString(1));
                    }
                }
            } finally {
                // set_config(..., true) is transaction-local; rolling back guarantees nothing
                // lingers on the connection.
                connection.rollback();
            }
        }
        return labels;
    }
}
