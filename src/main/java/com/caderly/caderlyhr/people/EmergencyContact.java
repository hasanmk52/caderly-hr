package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/** One emergency contact (PRD §6.3 FR-3.6) — multiple rows per employee, self-editable. */
@Entity
@Table(name = "emergency_contact")
public class EmergencyContact extends TenantAwareEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "relationship", length = 50)
    private @Nullable String relationship;

    @Column(name = "phone", length = 30)
    private @Nullable String phone;

    @Column(name = "email")
    private @Nullable String email;

    protected EmergencyContact() {}

    private EmergencyContact(
            Employee employee,
            String name,
            @Nullable String relationship,
            @Nullable String phone,
            @Nullable String email) {
        this.employee = employee;
        this.name = name;
        this.relationship = relationship;
        this.phone = phone;
        this.email = email;
    }

    public static EmergencyContact create(
            Employee employee,
            String name,
            @Nullable String relationship,
            @Nullable String phone,
            @Nullable String email) {
        return new EmergencyContact(employee, name, relationship, phone, email);
    }

    public Employee employee() {
        return employee;
    }

    public String name() {
        return name;
    }

    public @Nullable String relationship() {
        return relationship;
    }

    public @Nullable String phone() {
        return phone;
    }

    public @Nullable String email() {
        return email;
    }
}
