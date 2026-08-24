package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Append-only manager-assignment log (PRD §6.3 FR-3.4). Same open/close shape as {@link
 * EmployeeStatusHistory}.
 */
@Entity
@Table(name = "employee_manager_history")
public class EmployeeManagerHistory extends TenantAwareEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private @Nullable Employee manager;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private @Nullable LocalDate effectiveTo;

    protected EmployeeManagerHistory() {}

    private EmployeeManagerHistory(Employee employee, @Nullable Employee manager, LocalDate effectiveFrom) {
        this.employee = employee;
        this.manager = manager;
        this.effectiveFrom = effectiveFrom;
    }

    public static EmployeeManagerHistory open(
            Employee employee, @Nullable Employee manager, LocalDate effectiveFrom) {
        return new EmployeeManagerHistory(employee, manager, effectiveFrom);
    }

    public void close(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public @Nullable Employee manager() {
        return manager;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public @Nullable LocalDate effectiveTo() {
        return effectiveTo;
    }
}
