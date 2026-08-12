package com.helyx.helyxhr.people;

import com.helyx.helyxhr.common.CryptoConverter;
import com.helyx.helyxhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * A government-issued ID (PRD §6.3 FR-3.7) — multiple rows per employee. {@code idNumber} is
 * encrypted at rest (CLAUDE.md §6 A02, ADR 0008); visible only to Admin + the owning employee
 * (enforced in {@code EmployeeService}, not here).
 */
@Entity
@Table(name = "government_id")
public class GovernmentId extends TenantAwareEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", nullable = false, length = 50)
    private GovernmentIdType idType;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "id_number_encrypted", nullable = false)
    private String idNumber;

    @Column(name = "country", length = 100)
    private @Nullable String country;

    @Column(name = "issue_date")
    private @Nullable LocalDate issueDate;

    @Column(name = "expiry_date")
    private @Nullable LocalDate expiryDate;

    protected GovernmentId() {}

    private GovernmentId(
            Employee employee,
            GovernmentIdType idType,
            String idNumber,
            @Nullable String country,
            @Nullable LocalDate issueDate,
            @Nullable LocalDate expiryDate) {
        this.employee = employee;
        this.idType = idType;
        this.idNumber = idNumber;
        this.country = country;
        this.issueDate = issueDate;
        this.expiryDate = expiryDate;
    }

    public static GovernmentId create(
            Employee employee,
            GovernmentIdType idType,
            String idNumber,
            @Nullable String country,
            @Nullable LocalDate issueDate,
            @Nullable LocalDate expiryDate) {
        return new GovernmentId(employee, idType, idNumber, country, issueDate, expiryDate);
    }

    public Employee employee() {
        return employee;
    }

    public GovernmentIdType idType() {
        return idType;
    }

    public String idNumber() {
        return idNumber;
    }

    public @Nullable String country() {
        return country;
    }

    public @Nullable LocalDate issueDate() {
        return issueDate;
    }

    public @Nullable LocalDate expiryDate() {
        return expiryDate;
    }
}
