package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Append-only status log (PRD §6.3 FR-3.3). {@link EmployeeService} keeps at most one row per
 * employee with {@code effectiveTo == null} — the currently-open period — closing it via {@link
 * #close} whenever a new row opens.
 */
@Entity
@Table(name = "employee_status_history")
public class EmployeeStatusHistory extends TenantAwareEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmployeeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", length = 20)
    private @Nullable EmploymentType employmentType;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private @Nullable LocalDate effectiveTo;

    protected EmployeeStatusHistory() {}

    private EmployeeStatusHistory(
            Employee employee,
            EmployeeStatus status,
            @Nullable EmploymentType employmentType,
            LocalDate effectiveFrom) {
        this.employee = employee;
        this.status = status;
        this.employmentType = employmentType;
        this.effectiveFrom = effectiveFrom;
    }

    public static EmployeeStatusHistory open(
            Employee employee,
            EmployeeStatus status,
            @Nullable EmploymentType employmentType,
            LocalDate effectiveFrom) {
        return new EmployeeStatusHistory(employee, status, employmentType, effectiveFrom);
    }

    public void close(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public EmployeeStatus status() {
        return status;
    }

    public @Nullable EmploymentType employmentType() {
        return employmentType;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public @Nullable LocalDate effectiveTo() {
        return effectiveTo;
    }
}
