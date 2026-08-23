package com.helyx.helyxhr.people;

import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.Role;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Grants/revokes {@code MANAGER} on the affected {@code AppUser}(s) after a manager reassignment
 * (PRD §5, ADR 0011). Plain {@code @EventListener}, not {@code
 * @TransactionalEventListener(AFTER_COMMIT)} like {@code EmployeeInviteAcceptedListener}: this
 * fires from inside the same {@code @Transactional} method that changed {@code manager_id}
 * ({@link EmployeeService#reassignManagerInternal}), so there is no cross-transaction concern to
 * work around — mirrors {@code EmployeeHiredEventListener}'s reasoning instead, since the role
 * write is an internal DB write to a different aggregate that must stay consistent with the
 * {@code manager_id} write it results from.
 */
@Component
class ManagerRoleSyncListener {

    private final AppUserRepository appUsers;

    ManagerRoleSyncListener(AppUserRepository appUsers) {
        this.appUsers = appUsers;
    }

    @EventListener
    void onManagerRoleSync(ManagerRoleSyncEvent event) {
        if (event.newManagerUserId() != null) {
            appUsers.findById(event.newManagerUserId()).ifPresent(this::grant);
        }
        boolean sameManager =
                event.oldManagerUserId() != null && event.oldManagerUserId().equals(event.newManagerUserId());
        // sameManager guards a redundant "reassign to the same manager" call from immediately
        // revoking what it just granted above.
        if (event.oldManagerUserId() != null && !sameManager && !event.oldManagerRetainsOtherReports()) {
            appUsers.findById(event.oldManagerUserId()).ifPresent(this::revoke);
        }
    }

    private void grant(AppUser user) {
        user.grant(Role.MANAGER);
        appUsers.save(user);
    }

    private void revoke(AppUser user) {
        user.revoke(Role.MANAGER);
        appUsers.save(user);
    }
}
