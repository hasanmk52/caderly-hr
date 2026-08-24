package com.caderly.caderlyhr.timeoff;

import com.caderly.caderlyhr.people.EmployeeTerminatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Cancels a terminated employee's future leave requests and credits back any approved duration
 * (PRD §12.4 step 7, §14.4). Decoupled via an event — mirrors {@link EmployeeHiredEventListener}'s
 * doc comment exactly, same reasoning.
 *
 * <p>Plain {@code @EventListener}, not {@code @TransactionalEventListener(AFTER_COMMIT)}: this
 * cascade is an internal DB write that should be atomic with the termination itself, not a side
 * effect surviving it independently.
 */
@Component
class EmployeeTerminatedEventListener {

    private final LeaveRequestService leaveRequests;

    EmployeeTerminatedEventListener(LeaveRequestService leaveRequests) {
        this.leaveRequests = leaveRequests;
    }

    @EventListener
    void onEmployeeTerminated(EmployeeTerminatedEvent event) {
        leaveRequests.cancelFutureRequestsForTermination(event.employeeId(), event.effectiveDate());
    }
}
