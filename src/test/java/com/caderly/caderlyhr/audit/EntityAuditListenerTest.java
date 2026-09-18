package com.caderly.caderlyhr.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.audit.system.AuditEntry;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.PasswordResetToken;
import com.caderly.caderlyhr.identity.PasswordResetTokenRepository;
import com.caderly.caderlyhr.org.Division;
import com.caderly.caderlyhr.org.DivisionRepository;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeRepository;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves {@link EntityAuditListener} actually writes {@code audit_entry} rows (CURRENT_PHASE.md's DoD:
 * "a compensation update ... produces an audit_entry row"), on representative CREATE/UPDATE/DELETE
 * cases rather than one test per audited entity — the listener is wired broadly (ADR 0017), and
 * these three cases exercise the mechanism, not each entity's own business logic.
 */
class EntityAuditListenerTest extends TenantIsolationTestBase {

    @Autowired private DivisionRepository divisions;
    @Autowired private EmployeeRepository employees;
    @Autowired private PasswordResetTokenRepository resetTokens;
    @Autowired private AppUserRepository users;
    @Autowired private AuditEntryRepository auditEntries;
    @Autowired private TransactionTemplate transactions;

    @Test
    void create_writesAnAuditEntryWithNoBeforeState() {
        UUID divisionId =
                asTenant(
                        tenantA,
                        () -> transactions.execute(status -> divisions.save(Division.create("Engineering", null)).getId()));

        List<AuditEntry> rows = findFor(tenantA, "Division", divisionId);
        assertThat(rows).hasSize(1);
        AuditEntry row = rows.get(0);
        assertThat(row.action()).isEqualTo(Action.CREATE);
        assertThat(row.beforeJson()).isNull();
        assertThat(row.afterJson()).contains("Engineering");
        assertThat(row.tenantId()).isEqualTo(tenantA);
    }

    @Test
    void update_writesAnAuditEntryWithBothBeforeAndAfterState() {
        UUID divisionId =
                asTenant(
                        tenantA,
                        () -> transactions.execute(status -> divisions.save(Division.create("Sales", null)).getId()));

        asTenant(
                tenantA,
                () ->
                        transactions.execute(
                                status -> {
                                    Division division = divisions.findById(divisionId).orElseThrow();
                                    division.rename("Sales & Marketing");
                                    return divisions.save(division);
                                }));

        List<AuditEntry> rows = findFor(tenantA, "Division", divisionId);
        AuditEntry update = rows.stream().filter(r -> r.action() == Action.UPDATE).findFirst().orElseThrow();
        assertThat(update.beforeJson()).contains("Sales").doesNotContain("Marketing");
        assertThat(update.afterJson()).contains("Sales & Marketing");
    }

    @Test
    void delete_writesAnAuditEntryWithNoAfterState() {
        UUID divisionId =
                asTenant(
                        tenantA,
                        () -> transactions.execute(status -> divisions.save(Division.create("Temp", null)).getId()));

        asTenant(
                tenantA,
                () ->
                        transactions.execute(
                                status -> {
                                    divisions.delete(divisions.findById(divisionId).orElseThrow());
                                    return null;
                                }));

        List<AuditEntry> rows = findFor(tenantA, "Division", divisionId);
        AuditEntry delete = rows.stream().filter(r -> r.action() == Action.DELETE).findFirst().orElseThrow();
        assertThat(delete.beforeJson()).contains("Temp");
        assertThat(delete.afterJson()).isNull();
    }

    @Test
    void update_onAnEncryptedField_redactsItInsteadOfLeakingPlaintext() {
        UUID employeeId =
                asTenant(
                        tenantA,
                        () ->
                                transactions.execute(
                                        status -> employees.save(Employee.create("Priya", "Sharma", uniqueEmail())).getId()));

        asTenant(
                tenantA,
                () ->
                        transactions.execute(
                                status -> {
                                    Employee employee = employees.findById(employeeId).orElseThrow();
                                    employee.updateAdminFields(
                                            null,
                                            "Priya",
                                            "Sharma",
                                            employee.email(),
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            BigDecimal.valueOf(8.0),
                                            "USD",
                                            "95000.00");
                                    return employees.save(employee);
                                }));

        List<AuditEntry> rows = findFor(tenantA, "Employee", employeeId);
        AuditEntry update = rows.stream().filter(r -> r.action() == Action.UPDATE).findFirst().orElseThrow();
        assertThat(update.afterJson()).doesNotContain("95000.00").contains("REDACTED");
    }

    @Test
    void write_onAnExcludedEntity_producesNoAuditEntry() {
        // identity.PasswordResetToken is the one TenantAwareEntity deliberately excluded from
        // @EntityListeners(EntityAuditListener.class) — ADR 0017's "noise, not signal" reasoning.
        UUID userId =
                asTenant(
                        tenantA,
                        () ->
                                transactions.execute(
                                        status -> users.save(AppUser.active(uniqueEmail(), "hash")).getId()));

        asTenant(
                tenantA,
                () ->
                        transactions.execute(
                                status -> {
                                    AppUser user = users.findById(userId).orElseThrow();
                                    return resetTokens.save(
                                            new PasswordResetToken(user, "some-hash", Instant.now().plusSeconds(3600)));
                                }));

        assertThat(findFor(tenantA, "PasswordResetToken", null)).isEmpty();
    }

    /**
     * {@code audit_entry} has no RLS/{@code @TenantId} (ADR 0005 decision B), so a plain {@code
     * findAll()} works from any tenant context — {@code runAsSystem} here is just to avoid
     * depending on whichever tenant happens to be active when this helper is called.
     */
    private List<AuditEntry> findFor(UUID tenantId, String entityType, @org.jspecify.annotations.Nullable UUID entityId) {
        return com.caderly.caderlyhr.tenant.TenantContext.runAsSystem(
                "test: read audit_entry",
                () ->
                        auditEntries.findAll().stream()
                                .filter(e -> tenantId.equals(e.tenantId()))
                                .filter(e -> entityType.equals(e.entityType()))
                                .filter(e -> entityId == null || entityId.toString().equals(e.entityId()))
                                .toList());
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }
}
