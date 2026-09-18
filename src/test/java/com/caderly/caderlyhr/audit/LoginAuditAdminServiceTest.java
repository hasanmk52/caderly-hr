package com.caderly.caderlyhr.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.audit.system.LoginAudit;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Same tenant-boundary shape as {@link AuditAdminServiceTest}: {@code login_audit} has no
 * RLS/{@code @TenantId} either (ADR 0005 decision B), so this is the only place the isolation is
 * enforced.
 */
class LoginAuditAdminServiceTest extends TenantIsolationTestBase {

    @Autowired private LoginAuditAdminService logins;
    @Autowired private LoginAuditService recorder;

    @Test
    void list_showsOnlyTheCurrentTenantsAttempts() {
        asTenant(
                tenantA,
                () ->
                        recorder.record(
                                tenantA, null, "a@tenant-a.test", false, LoginAudit.FailureReason.UNKNOWN_EMAIL, "1.1.1.1", "ua"));
        asTenant(
                tenantB,
                () ->
                        recorder.record(
                                tenantB, null, "b@tenant-b.test", false, LoginAudit.FailureReason.UNKNOWN_EMAIL, "2.2.2.2", "ua"));

        assertThat(asTenant(tenantA, () -> logins.list(null, null, null, 0)).getContent())
                .extracting(LoginAuditAdminService.LoginAuditRow::emailAttempted)
                .containsExactly("a@tenant-a.test");
        assertThat(asTenant(tenantB, () -> logins.list(null, null, null, 0)).getContent())
                .extracting(LoginAuditAdminService.LoginAuditRow::emailAttempted)
                .containsExactly("b@tenant-b.test");
    }

    @Test
    void list_whenFilteredBySuccess_returnsOnlyThatOutcome() {
        asTenant(
                tenantA,
                () -> recorder.record(tenantA, null, "ok@tenant-a.test", true, null, "1.1.1.1", "ua"));
        asTenant(
                tenantA,
                () ->
                        recorder.record(
                                tenantA,
                                null,
                                "bad@tenant-a.test",
                                false,
                                LoginAudit.FailureReason.UNKNOWN_EMAIL,
                                "1.1.1.1",
                                "ua"));

        assertThat(asTenant(tenantA, () -> logins.list(true, null, null, 0)).getContent())
                .extracting(LoginAuditAdminService.LoginAuditRow::emailAttempted)
                .containsExactly("ok@tenant-a.test");
    }
}
