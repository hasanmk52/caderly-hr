package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/** One entry in an employee's education history (PRD §6.3 FR-3.5) — multiple rows per employee. */
@Entity
@Table(name = "education")
public class Education extends TenantAwareEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "institution", length = 200)
    private @Nullable String institution;

    @Column(name = "degree", length = 150)
    private @Nullable String degree;

    @Column(name = "field", length = 150)
    private @Nullable String field;

    @Column(name = "start_year")
    private @Nullable Integer startYear;

    @Column(name = "end_year")
    private @Nullable Integer endYear;

    protected Education() {}

    private Education(
            Employee employee,
            @Nullable String institution,
            @Nullable String degree,
            @Nullable String field,
            @Nullable Integer startYear,
            @Nullable Integer endYear) {
        this.employee = employee;
        this.institution = institution;
        this.degree = degree;
        this.field = field;
        this.startYear = startYear;
        this.endYear = endYear;
    }

    public static Education create(
            Employee employee,
            @Nullable String institution,
            @Nullable String degree,
            @Nullable String field,
            @Nullable Integer startYear,
            @Nullable Integer endYear) {
        return new Education(employee, institution, degree, field, startYear, endYear);
    }

    public Employee employee() {
        return employee;
    }

    public @Nullable String institution() {
        return institution;
    }

    public @Nullable String degree() {
        return degree;
    }

    public @Nullable String field() {
        return field;
    }

    public @Nullable Integer startYear() {
        return startYear;
    }

    public @Nullable Integer endYear() {
        return endYear;
    }
}
