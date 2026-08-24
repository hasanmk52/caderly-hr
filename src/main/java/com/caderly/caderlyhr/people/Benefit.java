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

/** One benefit assignment (PRD §6.3 FR-3.2) — multiple rows per employee, Admin-managed. */
@Entity
@Table(name = "benefit")
public class Benefit extends TenantAwareEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "name", length = 150)
    private @Nullable String name;

    @Column(name = "category", length = 100)
    private @Nullable String category;

    @Column(name = "start_date")
    private @Nullable LocalDate startDate;

    @Column(name = "end_date")
    private @Nullable LocalDate endDate;

    protected Benefit() {}

    private Benefit(
            Employee employee,
            @Nullable String name,
            @Nullable String category,
            @Nullable LocalDate startDate,
            @Nullable LocalDate endDate) {
        this.employee = employee;
        this.name = name;
        this.category = category;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public static Benefit create(
            Employee employee,
            @Nullable String name,
            @Nullable String category,
            @Nullable LocalDate startDate,
            @Nullable LocalDate endDate) {
        return new Benefit(employee, name, category, startDate, endDate);
    }

    public Employee employee() {
        return employee;
    }

    public @Nullable String name() {
        return name;
    }

    public @Nullable String category() {
        return category;
    }

    public @Nullable LocalDate startDate() {
        return startDate;
    }

    public @Nullable LocalDate endDate() {
        return endDate;
    }
}
