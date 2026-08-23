package com.helyx.helyxhr.people;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Published whenever {@link EmployeeService#reassignManagerInternal} changes an employee's
 * manager (PRD §5: "Managers are derived... user X is a 'manager' if any other user has
 * manager_id = X.id"). Carries {@code AppUser} ids, not {@code Employee} ids, so {@link
 * ManagerRoleSyncListener} needs nothing but {@code identity.AppUserRepository} — whether the old
 * manager still has other reports is answered by {@code EmployeeRepository} <em>before</em> this
 * event is built, never recomputed by the listener.
 *
 * <p>Self-published and self-consumed within {@code people}: unlike {@link EmployeeHiredEvent}
 * (people -&gt; timeoff) or {@code identity.UserInviteAcceptedEvent} (identity -&gt; people), there is
 * no package-cycle reason for an event here — {@code EmployeeService} already imports {@code
 * identity} directly (ADR 0011). It stays an event anyway, mirroring those two, so role
 * bookkeeping doesn't get hand-mixed into the manager-reassignment method it results from.
 */
record ManagerRoleSyncEvent(
        @Nullable UUID oldManagerUserId, boolean oldManagerRetainsOtherReports, @Nullable UUID newManagerUserId) {}
