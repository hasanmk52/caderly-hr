package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.TenantAwareRepository;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

/** No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4). */
@Repository
public interface LeaveBalanceRepository extends TenantAwareRepository<LeaveBalance> {

    /** {@code BalanceService}'s idempotency check (PRD §12.2) — one row per employee/type/year. */
    boolean existsByEmployeeIdAndLeaveTypeIdAndYear(UUID employeeId, UUID leaveTypeId, int year);

    /** Backs the manual-adjustment endpoint, which only adjusts an existing balance row. */
    Optional<LeaveBalance> findByEmployeeIdAndLeaveTypeIdAndYear(
            UUID employeeId, UUID leaveTypeId, int year);

    /**
     * Pessimistic write lock variant of {@link #findByEmployeeIdAndLeaveTypeIdAndYear}, used only
     * by {@code LeaveRequestService.book()}'s balance-check-then-insert critical section — a
     * second concurrent booking for the same employee/leave type/year blocks here until the
     * first transaction commits, then re-reads and correctly sees the first's now-committed
     * PENDING request via a fresh {@code sumPendingDuration} query. Not used by the read-only
     * {@code preview()} path or by {@code BalanceService} — those don't need to serialize.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LeaveBalance> findForUpdateByEmployeeIdAndLeaveTypeIdAndYear(
            UUID employeeId, UUID leaveTypeId, int year);

    /**
     * {@code leaveType} is {@code FetchType.LAZY} and {@code spring.jpa.open-in-view: false}
     * means no Hibernate session survives past the owning {@code @Transactional} method — fetch
     * it eagerly here so {@code home.html}'s balance cards don't hit
     * {@code LazyInitializationException} (the exact bug fixed for Employee.department/manager in
     * commit af5af38 — same cause, same fix).
     */
    @EntityGraph(attributePaths = "leaveType")
    List<LeaveBalance> findAllByEmployeeIdAndYear(UUID employeeId, int year);
}
