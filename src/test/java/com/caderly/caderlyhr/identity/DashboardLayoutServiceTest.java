package com.caderly.caderlyhr.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.identity.DashboardLayoutService.WidgetSlot;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Persistence and CLAUDE.md §5 rule 8 isolation for {@link DashboardLayoutService}. */
class DashboardLayoutServiceTest extends TenantIsolationTestBase {

    @Autowired private DashboardLayoutService service;
    @Autowired private AppUserRepository appUsers;

    @Test
    void layoutFor_whenNeverSaved_returnsDefault() {
        UUID user = createUser(tenantA);

        List<WidgetSlot> slots = asTenant(tenantA, () -> service.layoutFor(user));

        assertThat(slots).extracting(WidgetSlot::widget).containsExactly(DashboardWidget.values());
        assertThat(slots).noneMatch(WidgetSlot::hidden);
    }

    @Test
    void save_thenLayoutFor_returnsSavedOrderAndHidden() {
        UUID user = createUser(tenantA);

        asTenant(tenantA, () -> service.save(user, List.of("resources", "my-peers"), List.of("my-peers")));
        List<WidgetSlot> slots = asTenant(tenantA, () -> service.layoutFor(user));

        assertThat(slots.get(0).widget()).isEqualTo(DashboardWidget.RESOURCES);
        assertThat(slots.get(1).widget()).isEqualTo(DashboardWidget.MY_PEERS);
        assertThat(slots.get(1).hidden()).isTrue();
    }

    @Test
    void save_twice_updatesTheSingleRow() {
        UUID user = createUser(tenantA);

        asTenant(tenantA, () -> service.save(user, List.of("resources"), List.of()));
        asTenant(tenantA, () -> service.save(user, List.of("my-peers"), List.of("resources")));
        List<WidgetSlot> slots = asTenant(tenantA, () -> service.layoutFor(user));

        assertThat(slots.get(0).widget()).isEqualTo(DashboardWidget.MY_PEERS);
        assertThat(slots).filteredOn(WidgetSlot::hidden).extracting(WidgetSlot::widget)
                .containsExactly(DashboardWidget.RESOURCES);
    }

    @Test
    void reset_afterSave_restoresDefault() {
        UUID user = createUser(tenantA);
        asTenant(tenantA, () -> service.save(user, List.of("resources"), List.of("my-peers")));

        asTenant(tenantA, () -> service.reset(user));
        List<WidgetSlot> slots = asTenant(tenantA, () -> service.layoutFor(user));

        assertThat(slots).extracting(WidgetSlot::widget).containsExactly(DashboardWidget.values());
        assertThat(slots).noneMatch(WidgetSlot::hidden);
    }

    @Test
    void layoutFor_savedInTenantA_isInvisibleToTenantB() {
        UUID user = createUser(tenantA);
        asTenant(tenantA, () -> service.save(user, List.of("resources"), List.of("my-peers")));

        // Same user id read through tenant B's context: Hibernate's @TenantId filter hides the row.
        List<WidgetSlot> slots = asTenant(tenantB, () -> service.layoutFor(user));

        assertThat(slots).extracting(WidgetSlot::widget).containsExactly(DashboardWidget.values());
        assertThat(slots).noneMatch(WidgetSlot::hidden);
    }

    @Test
    void reset_inTenantB_doesNotTouchTenantALayout() {
        UUID user = createUser(tenantA);
        asTenant(tenantA, () -> service.save(user, List.of("resources"), List.of()));

        asTenant(tenantB, () -> service.reset(user));

        assertThat(asTenant(tenantA, () -> service.layoutFor(user)).get(0).widget())
                .isEqualTo(DashboardWidget.RESOURCES);
    }

    @Test
    void save_forOneUser_doesNotChangeAnotherUsersLayoutInSameTenant() {
        UUID alice = createUser(tenantA);
        UUID bob = createUser(tenantA);

        asTenant(tenantA, () -> service.save(alice, List.of("resources"), List.of("my-peers")));

        List<WidgetSlot> bobs = asTenant(tenantA, () -> service.layoutFor(bob));
        assertThat(bobs).extracting(WidgetSlot::widget).containsExactly(DashboardWidget.values());
        assertThat(bobs).noneMatch(WidgetSlot::hidden);
    }

    private UUID createUser(UUID tenantId) {
        return asTenant(
                tenantId,
                () -> appUsers.save(AppUser.active(UUID.randomUUID() + "@layout.test", "{noop}unused")).requireId());
    }
}
