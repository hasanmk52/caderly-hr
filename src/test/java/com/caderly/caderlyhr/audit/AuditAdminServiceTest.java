package com.caderly.caderlyhr.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.org.Division;
import com.caderly.caderlyhr.org.DivisionRepository;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code audit_entry} is system-scoped infrastructure with no RLS/{@code @TenantId} (ADR 0005
 * decision B), so — exactly like {@code NotificationAdminServiceTest} for {@code email_outbox} —
 * the isolation this file asserts is enforced nowhere else: delete the {@code tenantId} predicate
 * in {@link AuditAdminService} and nothing underneath it objects.
 */
class AuditAdminServiceTest extends TenantIsolationTestBase {

    @Autowired private AuditAdminService audits;
    @Autowired private DivisionRepository divisions;
    @Autowired private TransactionTemplate transactions;

    @Test
    void list_showsOnlyTheCurrentTenantsWrites() {
        UUID divisionIdA = createDivisionIn(tenantA, "Engineering A");
        createDivisionIn(tenantB, "Engineering B");

        assertThat(asTenant(tenantA, () -> audits.list(null, null, null, null, null, 0)).getContent())
                .extracting(AuditAdminService.AuditRow::entityId)
                .containsExactly(divisionIdA.toString());
    }

    @Test
    void list_whenFilteredByEntityType_returnsOnlyThatType() {
        createDivisionIn(tenantA, "Filtered");

        assertThat(
                        asTenant(tenantA, () -> audits.list("Division", null, null, null, null, 0))
                                .getContent())
                .isNotEmpty();
        assertThat(
                        asTenant(tenantA, () -> audits.list("Employee", null, null, null, null, 0))
                                .getContent())
                .isEmpty();
    }

    @Test
    void findOne_onAnotherTenantsRow_reportsNotFoundRatherThanForbidden() {
        createDivisionIn(tenantB, "Belongs To B");
        UUID rowId =
                asTenant(tenantB, () -> audits.list(null, null, null, null, null, 0))
                        .getContent()
                        .get(0)
                        .id();

        assertThatThrownBy(() -> asTenant(tenantA, () -> audits.findOne(rowId)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findOne_prettyPrintsTheStoredJson() {
        UUID divisionId = createDivisionIn(tenantA, "Pretty");

        UUID rowId =
                asTenant(tenantA, () -> audits.list("Division", null, null, null, null, 0))
                        .getContent()
                        .stream()
                        .filter(row -> divisionId.toString().equals(row.entityId()))
                        .findFirst()
                        .orElseThrow()
                        .id();

        AuditAdminService.AuditDiff diff = asTenant(tenantA, () -> audits.findOne(rowId));
        assertThat(diff.afterJson()).contains("\n");
    }

    private UUID createDivisionIn(UUID tenantId, String name) {
        return asTenant(
                tenantId,
                () -> transactions.execute(status -> divisions.save(Division.create(name, null)).getId()));
    }
}
