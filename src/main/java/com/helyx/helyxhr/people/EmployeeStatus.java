package com.helyx.helyxhr.people;

/**
 * Persisted lifecycle of an {@link Employee} (PRD §14.1, §21). {@code ON_LEAVE} is deliberately
 * absent: PRD §14.1 defines it as a derived/display state (an ACTIVE employee with an approved
 * leave spanning today), not a stored one — computed at read time once {@code leave_request}
 * exists (Phase 1.5/1.6), never persisted here.
 */
public enum EmployeeStatus {
    /** Created by an Admin, invite email sent, no login yet. */
    INVITED,

    /** Invite accepted (or created active by a system path); normal, usable record. */
    ACTIVE,

    /** Termination has taken effect. The row is retained, never deleted (PRD §14.4). */
    TERMINATED;

    /** Display label for UI dropdowns, filters, and status badges. */
    public String label() {
        return switch (this) {
            case INVITED -> "Invited";
            case ACTIVE -> "Active";
            case TERMINATED -> "Terminated";
        };
    }
}
