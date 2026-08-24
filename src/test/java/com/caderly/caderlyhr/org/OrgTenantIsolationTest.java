package com.caderly.caderlyhr.org;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
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

/**
 * CLAUDE.md §5 rule 8: every new tenant-scoped entity gets a test proving cross-tenant reads come
 * back empty. Covers both {@link Division} and {@link Department} added in sub-phase 1.3.
 */
class OrgTenantIsolationTest extends TenantIsolationTestBase {

    @Autowired private DivisionRepository divisions;
    @Autowired private DepartmentRepository departments;
    @Autowired private PostgreSQLContainer postgres;

    private String divisionNameA;
    private String divisionNameB;

    @BeforeEach
    void seedDivisions() {
        divisionNameA = "Division-A-" + UUID.randomUUID();
        divisionNameB = "Division-B-" + UUID.randomUUID();
        asTenant(tenantA, () -> divisions.save(Division.create(divisionNameA, null)));
        asTenant(tenantB, () -> divisions.save(Division.create(divisionNameB, null)));
    }

    @Test
    void findAll_whenTenantAActive_returnsOnlyTenantADivisions() {
        List<Division> visible = asTenant(tenantA, () -> divisions.findAll());

        assertThat(visible).isNotEmpty();
        assertThat(visible).allSatisfy(d -> assertThat(d.getTenantId()).isEqualTo(tenantA));
        assertThat(visible).extracting(Division::name).contains(divisionNameA).doesNotContain(divisionNameB);
    }

    @Test
    void save_whenTenantAActive_assignsTenantIdAutomatically() {
        Division saved = asTenant(tenantA, () -> divisions.save(Division.create("Auto", null)));

        assertThat(saved.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void findById_whenTenantBActiveOnTenantADivision_returnsEmpty() {
        UUID divisionAId = asTenant(tenantA, () -> divisions.findAll()).stream()
                .filter(d -> d.name().equals(divisionNameA))
                .findFirst()
                .orElseThrow()
                .getId();

        assertThat(asTenant(tenantB, () -> divisions.findById(divisionAId))).isEmpty();
    }

    @Test
    void existsByNameIgnoreCase_whenDivisionBelongsToOtherTenant_returnsFalse() {
        assertThat(asTenant(tenantB, () -> divisions.existsByNameIgnoreCase(divisionNameA))).isFalse();
        assertThat(asTenant(tenantA, () -> divisions.existsByNameIgnoreCase(divisionNameA))).isTrue();
    }

    @Test
    void departments_seededInTenantA_areInvisibleToTenantB() {
        UUID divisionAId = asTenant(tenantA, () -> divisions.findAll()).stream()
                .filter(d -> d.name().equals(divisionNameA))
                .findFirst()
                .orElseThrow()
                .getId();
        String departmentName = "Backend-" + UUID.randomUUID();
        asTenant(
                tenantA,
                () -> {
                    Division division = divisions.findById(divisionAId).orElseThrow();
                    return departments.save(Department.create(departmentName, null, division));
                });

        assertThat(asTenant(tenantA, () -> departments.existsByNameIgnoreCase(departmentName))).isTrue();
        assertThat(asTenant(tenantB, () -> departments.existsByNameIgnoreCase(departmentName))).isFalse();
        assertThat(asTenant(tenantA, () -> departments.findAll()))
                .allSatisfy(d -> assertThat(d.getTenantId()).isEqualTo(tenantA));
    }

    @Test
    void rawJdbc_withTenantASetting_rlsReturnsOnlyTenantADivisions() throws Exception {
        // Independent of Hibernate: proves the Postgres policy holds even if the ORM
        // restriction were bypassed. Uses the non-superuser rls_probe role.
        assertThat(selectDivisionNamesAsRestrictedRole(tenantA))
                .contains(divisionNameA)
                .doesNotContain(divisionNameB);
    }

    @Test
    void rawJdbc_withoutTenantSetting_rlsReturnsNoDivisions() throws Exception {
        assertThat(selectDivisionNamesAsRestrictedRole(null)).isEmpty();
    }

    private List<String> selectDivisionNamesAsRestrictedRole(UUID tenantId) throws Exception {
        List<String> names = new ArrayList<>();
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
                                connection.prepareStatement("SELECT name FROM caderly_hr.division");
                        ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        return names;
    }
}
