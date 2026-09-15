package com.caderly.caderlyhr.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import com.caderly.caderlyhr.tenant.TenantFacade.NotificationSettings;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** PRD FR-9.3: disabling a category must stop the event enqueuing, per tenant. */
class NotificationCategoryToggleTest extends TenantIsolationTestBase {

    @Autowired private EmailOutboxService outboxService;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private TenantFacade tenants;
    @Autowired private TransactionTemplate transactions;

    @BeforeEach
    void clearOutbox() {
        TenantContext.runAsSystem(
                "test: clear email outbox",
                () -> {
                    outbox.deleteAll();
                    return null;
                });
    }

    @Test
    void newTenant_hasBirthdayAndAnniversaryOff_becausePrdMarksThemOptIn() {
        NotificationSettings settings = asTenant(tenantA, tenants::currentNotificationSettings);

        assertThat(settings.holidayReminder()).isTrue();
        assertThat(settings.documentExpiry()).isTrue();
        assertThat(settings.birthday()).isFalse();
        assertThat(settings.workAnniversary()).isFalse();
    }

    @Test
    void enqueue_whenCategoryIsOff_writesNoRowAtAll() {
        // No row, rather than a suppressed one: a PENDING row parked behind a disabled category
        // would flush out the moment someone re-enabled it, weeks late.
        String recipient = "off-" + UUID.randomUUID() + "@example.test";

        Optional<UUID> queued = asTenant(tenantA, () -> enqueueBirthday(recipient));

        assertThat(queued).isEmpty();
        assertThat(recipients()).doesNotContain(recipient);
    }

    @Test
    void enqueue_whenCategoryIsOn_writesTheRow() {
        String recipient = "on-" + UUID.randomUUID() + "@example.test";
        asTenant(tenantA, () -> tenants.updateNotificationSettings(enableBirthday()));

        Optional<UUID> queued = asTenant(tenantA, () -> enqueueBirthday(recipient));

        assertThat(queued).isPresent();
        assertThat(recipients()).contains(recipient);
    }

    @Test
    void categories_areSwitchedPerTenant_notGlobally() {
        asTenant(tenantA, () -> tenants.updateNotificationSettings(enableBirthday()));
        String toA = "a-" + UUID.randomUUID() + "@example.test";
        String toB = "b-" + UUID.randomUUID() + "@example.test";

        asTenant(tenantA, () -> enqueueBirthday(toA));
        asTenant(tenantB, () -> enqueueBirthday(toB));

        assertThat(recipients()).contains(toA).doesNotContain(toB);
    }

    @Test
    void transactionalMail_hasNoCategoryAndIsNeverSuppressed() {
        // Every switchable category is off for tenantB, yet an invite must still go out: an
        // invite nobody receives is an account nobody can activate.
        String recipient = "invite-" + UUID.randomUUID() + "@example.test";

        Optional<UUID> queued =
                asTenant(
                        tenantB,
                        () ->
                                transactions.execute(
                                        status ->
                                                outboxService.enqueue(
                                                        EmailEvent.INVITE,
                                                        recipient,
                                                        Map.of("acceptUrl", "https://b.localhost/accept-invite?token=t"),
                                                        "Tenant B")));

        assertThat(queued).isPresent();
    }

    private Optional<UUID> enqueueBirthday(String recipient) {
        return transactions.execute(
                status ->
                        outboxService.enqueue(
                                EmailEvent.BIRTHDAY, recipient, Map.of("celebrantName", "Priya Nair"), "Priya Nair"));
    }

    private static NotificationSettings enableBirthday() {
        return new NotificationSettings(true, true, true, false);
    }

    private java.util.List<String> recipients() {
        return TenantContext.runAsSystem(
                "test: read outbox",
                () -> outbox.findAll().stream().map(row -> row.toEmail()).toList());
    }
}
