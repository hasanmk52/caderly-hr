package com.helyx.helyxhr.tenantisolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helyx.helyxhr.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
  void save_whenNoTenantContext_throws() {
    assertThatThrownBy(() -> probes.save("orphan")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void findAll_whenNoTenantContext_throws() {
    assertThatThrownBy(probes::findAll).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void findAll_inSystemMode_skipsTenantFilterAndSeesAllTenants() {
    // App-layer contract of runAsSystem: no tenant filter, so rows from every tenant
    // are visible. NOTE: this holds in tests because the Testcontainers datasource
    // user is a superuser (bypasses RLS); in dev/prod the app role is subject to RLS
    // and an unscoped system read returns nothing until a BYPASSRLS strategy lands
    // with the Super Admin console (ADR 0003).
    List<IsolationProbe> visible =
        TenantContext.runAsSystem("isolation test: unscoped read", probes::findAll);

    assertThat(visible).extracting(IsolationProbe::label).contains(labelA, labelB);
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
