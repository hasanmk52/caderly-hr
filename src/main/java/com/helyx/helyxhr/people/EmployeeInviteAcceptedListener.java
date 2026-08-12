package com.helyx.helyxhr.people;

import com.helyx.helyxhr.identity.UserInviteAcceptedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Flips the matching {@link Employee}'s status to ACTIVE once their login account's invite is
 * accepted (PRD §14.2). Decoupled via an event, not a direct call from {@code identity}, so
 * {@code identity} never needs to know {@code people} exists (see {@link
 * UserInviteAcceptedEvent}'s doc comment) — mirrors {@code security.PasswordChangeSessionRevoker}.
 */
@Component
class EmployeeInviteAcceptedListener {

    private final EmployeeService employees;

    EmployeeInviteAcceptedListener(EmployeeService employees) {
        this.employees = employees;
    }

    /** After commit, not during: an accept that then rolled back should not activate anyone. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onInviteAccepted(UserInviteAcceptedEvent event) {
        employees.activateForUser(event.userId());
    }
}
