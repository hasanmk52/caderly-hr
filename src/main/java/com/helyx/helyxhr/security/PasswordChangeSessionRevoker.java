package com.helyx.helyxhr.security;

import com.helyx.helyxhr.identity.PasswordChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Kills every existing session for a user whose password just changed (PRD §19.1) — otherwise a
 * stolen session survives the very change made to shut it out.
 */
@Component
class PasswordChangeSessionRevoker {

    private final SessionRevoker sessionRevoker;

    PasswordChangeSessionRevoker(SessionRevoker sessionRevoker) {
        this.sessionRevoker = sessionRevoker;
    }

    /**
     * After commit, not during: revoking sessions for a password change that then rolled back
     * would log the user out for no reason.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onPasswordChanged(PasswordChangedEvent event) {
        sessionRevoker.revokeAllFor(event.userId());
    }
}
