package com.caderly.caderlyhr.timeoff;

import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One employee's request to take time off (PRD §12.4, §21). {@code employeeId} is a plain id, not
 * a JPA relation, for the same reason as {@link LeaveBalance#employeeId()} — {@code timeoff} reads
 * {@code people} data only through {@code PeopleFacade} (CLAUDE.md §4). {@code deciderId}/{@code
 * cancelledBy} are plain ids referencing {@code identity.AppUser} (matching PRD §21's exact DDL,
 * which points these at {@code app_user}, not {@code employee}).
 *
 * <p>No public setters (CLAUDE.md §10): every mutation is a named transition, each first checked
 * by {@link LeaveRequestStateMachine}. No optimistic-locking {@code @Version} here (unlike {@link
 * LeaveBalance}) — PRD §21's schema for this table has no version column, and the balance row is
 * the one multiple concurrent actions actually race on.
 */
@Entity
@Table(name = "leave_request")
public class LeaveRequest extends TenantAwareEntity {

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "start_half_day_pm", nullable = false)
    private boolean startHalfDayPm;

    @Column(name = "end_half_day_am", nullable = false)
    private boolean endHalfDayAm;

    @Column(name = "duration_days", nullable = false)
    private BigDecimal durationDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LeaveRequestStatus status;

    @Column(name = "note")
    private @Nullable String note;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "decided_at")
    private @Nullable Instant decidedAt;

    @Column(name = "decider_id")
    private @Nullable UUID deciderId;

    @Column(name = "decision_note")
    private @Nullable String decisionNote;

    @Column(name = "cancelled_at")
    private @Nullable Instant cancelledAt;

    @Column(name = "cancelled_by")
    private @Nullable UUID cancelledBy;

    protected LeaveRequest() {}

    private LeaveRequest(
            UUID employeeId,
            LeaveType leaveType,
            LocalDate startDate,
            LocalDate endDate,
            boolean startHalfDayPm,
            boolean endHalfDayAm,
            BigDecimal durationDays,
            @Nullable String note,
            Instant submittedAt) {
        this.employeeId = employeeId;
        this.leaveType = leaveType;
        this.startDate = startDate;
        this.endDate = endDate;
        this.startHalfDayPm = startHalfDayPm;
        this.endHalfDayAm = endHalfDayAm;
        this.durationDays = durationDays;
        this.note = note;
        this.submittedAt = submittedAt;
        this.status = LeaveRequestStatus.PENDING;
    }

    public static LeaveRequest submit(
            UUID employeeId,
            LeaveType leaveType,
            LocalDate startDate,
            LocalDate endDate,
            boolean startHalfDayPm,
            boolean endHalfDayAm,
            BigDecimal durationDays,
            @Nullable String note,
            Instant submittedAt) {
        return new LeaveRequest(
                employeeId,
                leaveType,
                startDate,
                endDate,
                startHalfDayPm,
                endHalfDayAm,
                durationDays,
                note,
                submittedAt);
    }

    public void approve(UUID deciderId, @Nullable String decisionNote, Instant decidedAt) {
        LeaveRequestStateMachine.requireTransition(status, LeaveRequestStatus.APPROVED);
        this.status = LeaveRequestStatus.APPROVED;
        this.deciderId = deciderId;
        this.decisionNote = decisionNote;
        this.decidedAt = decidedAt;
    }

    public void reject(UUID deciderId, @Nullable String decisionNote, Instant decidedAt) {
        LeaveRequestStateMachine.requireTransition(status, LeaveRequestStatus.REJECTED);
        this.status = LeaveRequestStatus.REJECTED;
        this.deciderId = deciderId;
        this.decisionNote = decisionNote;
        this.decidedAt = decidedAt;
    }

    public void cancel(UUID cancelledByUserId, Instant cancelledAt) {
        LeaveRequestStateMachine.requireTransition(status, LeaveRequestStatus.CANCELLED);
        this.status = LeaveRequestStatus.CANCELLED;
        this.cancelledBy = cancelledByUserId;
        this.cancelledAt = cancelledAt;
    }

    /**
     * Termination auto-cancel (PRD §12.4 step 7, §14.4). No human actor — {@code cancelledBy}
     * stays null and the row carries a system note instead of an email event (decision 7 in the
     * phase plan).
     */
    public void systemCancel(Instant cancelledAt) {
        LeaveRequestStateMachine.requireTransition(status, LeaveRequestStatus.CANCELLED);
        this.status = LeaveRequestStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
        this.decisionNote = "Automatically cancelled: employee terminated";
    }

    public UUID employeeId() {
        return employeeId;
    }

    public LeaveType leaveType() {
        return leaveType;
    }

    public LocalDate startDate() {
        return startDate;
    }

    public LocalDate endDate() {
        return endDate;
    }

    public boolean startHalfDayPm() {
        return startHalfDayPm;
    }

    public boolean endHalfDayAm() {
        return endHalfDayAm;
    }

    public BigDecimal durationDays() {
        return durationDays;
    }

    public LeaveRequestStatus status() {
        return status;
    }

    public @Nullable String note() {
        return note;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public @Nullable Instant decidedAt() {
        return decidedAt;
    }

    public @Nullable UUID deciderId() {
        return deciderId;
    }

    public @Nullable String decisionNote() {
        return decisionNote;
    }

    public @Nullable Instant cancelledAt() {
        return cancelledAt;
    }

    public @Nullable UUID cancelledBy() {
        return cancelledBy;
    }
}
