package com.caderly.caderlyhr.timeoff;

import com.caderly.caderlyhr.common.ConflictException;

/**
 * Legal {@link LeaveRequestStatus} transitions (PRD §12.4, §21; CLAUDE.md §10 state-transition
 * recipe). PENDING is the only non-terminal status besides APPROVED (which may still be
 * cancelled) — REJECTED and CANCELLED never move again.
 */
final class LeaveRequestStateMachine {

    private LeaveRequestStateMachine() {}

    static void requireTransition(LeaveRequestStatus from, LeaveRequestStatus to) {
        if (!isLegal(from, to)) {
            throw new ConflictException(
                    "LEAVE_REQUEST_ILLEGAL_TRANSITION",
                    "Cannot move a leave request from " + from + " to " + to);
        }
    }

    private static boolean isLegal(LeaveRequestStatus from, LeaveRequestStatus to) {
        return switch (from) {
            case PENDING ->
                    to == LeaveRequestStatus.APPROVED
                            || to == LeaveRequestStatus.REJECTED
                            || to == LeaveRequestStatus.CANCELLED;
            case APPROVED -> to == LeaveRequestStatus.CANCELLED;
            case REJECTED, CANCELLED -> false;
        };
    }
}
