package com.caderly.caderlyhr.timeoff;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4). */
@Repository
public interface LeaveRequestRepository extends TenantAwareRepository<LeaveRequest> {

    /**
     * {@code leaveType} is {@code FetchType.LAZY} and {@code spring.jpa.open-in-view: false} —
     * same reasoning as {@code LeaveBalanceRepository#findAllByEmployeeIdAndYear}.
     */
    @EntityGraph(attributePaths = "leaveType")
    @Override
    Optional<LeaveRequest> findById(UUID id);

    @EntityGraph(attributePaths = "leaveType")
    List<LeaveRequest> findAllByEmployeeIdOrderBySubmittedAtDesc(UUID employeeId);

    @EntityGraph(attributePaths = "leaveType")
    List<LeaveRequest> findAllByStatusOrderBySubmittedAtAsc(LeaveRequestStatus status);

    @EntityGraph(attributePaths = "leaveType")
    List<LeaveRequest> findAllByDeciderIdOrderByDecidedAtDesc(UUID deciderId);

    /** Termination cascade: an employee's still-actionable requests from the effective date on. */
    @EntityGraph(attributePaths = "leaveType")
    List<LeaveRequest> findAllByEmployeeIdAndStatusInAndStartDateGreaterThanEqual(
            UUID employeeId, List<LeaveRequestStatus> statuses, LocalDate startDate);

    /**
     * BR-4's "pending + requested > remaining" check. Cross-year bookings are disallowed (Phase
     * 1.6 plan decision 4), so a plain {@code startDate BETWEEN} the calendar year's bounds is
     * equivalent to, and simpler than, filtering by an extracted year.
     */
    @Query(
            """
            SELECT COALESCE(SUM(r.durationDays), 0)
            FROM LeaveRequest r
            WHERE r.employeeId = :employeeId
              AND r.leaveType.id = :leaveTypeId
              AND r.status = com.caderly.caderlyhr.timeoff.LeaveRequestStatus.PENDING
              AND r.startDate BETWEEN :yearStart AND :yearEnd
            """)
    BigDecimal sumPendingDuration(
            @Param("employeeId") UUID employeeId,
            @Param("leaveTypeId") UUID leaveTypeId,
            @Param("yearStart") LocalDate yearStart,
            @Param("yearEnd") LocalDate yearEnd);

    /**
     * BR-15's overlap check: any of the employee's own requests in one of the given statuses
     * whose [startDate, endDate] range intersects [start, end] (inclusive), regardless of leave
     * type.
     */
    @Query(
            """
            SELECT COUNT(r) > 0
            FROM LeaveRequest r
            WHERE r.employeeId = :employeeId
              AND r.status IN :statuses
              AND r.startDate <= :end
              AND r.endDate >= :start
            """)
    boolean existsOverlapping(
            @Param("employeeId") UUID employeeId,
            @Param("statuses") List<LeaveRequestStatus> statuses,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);
}
