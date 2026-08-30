package com.caderly.caderlyhr.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarTokenServiceTest extends TenantIsolationTestBase {

    @Autowired private CalendarTokenService tokenService;
    @Autowired private AppUserRepository users;

    @Test
    void getOrCreateToken_whenNoneYet_generatesAndPersistsOne() {
        UUID userId = asTenant(tenantA, () -> users.save(AppUser.active(uniqueEmail(), "hash"))).getId();

        String token = asTenant(tenantA, () -> tokenService.getOrCreateToken(userId));

        assertThat(token).isNotBlank();
        assertThat(asTenant(tenantA, () -> users.findById(userId)).orElseThrow().icalToken())
                .isEqualTo(token);
    }

    @Test
    void getOrCreateToken_whenCalledTwice_returnsTheSameToken() {
        UUID userId = asTenant(tenantA, () -> users.save(AppUser.active(uniqueEmail(), "hash"))).getId();

        String first = asTenant(tenantA, () -> tokenService.getOrCreateToken(userId));
        String second = asTenant(tenantA, () -> tokenService.getOrCreateToken(userId));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void regenerateToken_replacesTheExistingToken() {
        UUID userId = asTenant(tenantA, () -> users.save(AppUser.active(uniqueEmail(), "hash"))).getId();
        String original = asTenant(tenantA, () -> tokenService.getOrCreateToken(userId));

        String regenerated = asTenant(tenantA, () -> tokenService.regenerateToken(userId));

        assertThat(regenerated).isNotEqualTo(original);
        assertThat(asTenant(tenantA, () -> users.findById(userId)).orElseThrow().icalToken())
                .isEqualTo(regenerated);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
