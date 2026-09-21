package com.caderly.caderlyhr.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link Tenant} — no Spring context needed. */
class TenantTest {

    @Test
    void softDelete_setsDeletedAt() {
        Tenant tenant = new Tenant("acme", "Acme Inc");
        assertThat(tenant.getDeletedAt()).isNull();

        Instant when = Instant.parse("2026-09-21T10:00:00Z");
        tenant.softDelete(when);

        assertThat(tenant.getDeletedAt()).isEqualTo(when);
    }

    @Test
    void fiveArgConstructor_setsGivenFieldsAndDefaultsTheRestLikeTheTwoArgConstructor() {
        Tenant tenant = new Tenant("acme", "Acme Inc", "Asia/Kolkata", 32, "https://logo.example/acme.png");

        assertThat(tenant.getSlug()).isEqualTo("acme");
        assertThat(tenant.getName()).isEqualTo("Acme Inc");
        assertThat(tenant.getTimezone()).isEqualTo("Asia/Kolkata");
        assertThat(tenant.getWeekendDays()).isEqualTo(32);
        assertThat(tenant.getLogoUrl()).isEqualTo("https://logo.example/acme.png");

        // Same defaults the 2-arg constructor applies to everything it doesn't take as a parameter.
        Tenant twoArg = new Tenant("other", "Other Inc");
        assertThat(tenant.getLocale()).isEqualTo(twoArg.getLocale());
        assertThat(tenant.isSuspended()).isEqualTo(twoArg.isSuspended());
        assertThat(tenant.isNotifyHolidayReminder()).isEqualTo(twoArg.isNotifyHolidayReminder());
        assertThat(tenant.isNotifyDocumentExpiry()).isEqualTo(twoArg.isNotifyDocumentExpiry());
        assertThat(tenant.isNotifyBirthday()).isEqualTo(twoArg.isNotifyBirthday());
        assertThat(tenant.isNotifyWorkAnniversary()).isEqualTo(twoArg.isNotifyWorkAnniversary());
    }

    @Test
    void fiveArgConstructor_withNullLogoUrl_isAllowed() {
        Tenant tenant = new Tenant("acme", "Acme Inc", "UTC", 96, null);

        assertThat(tenant.getLogoUrl()).isNull();
    }
}
