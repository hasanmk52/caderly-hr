package com.helyx.helyxhr.people;

import com.helyx.helyxhr.common.CryptoConverter;
import com.helyx.helyxhr.common.TenantAwareEntity;
import com.helyx.helyxhr.org.Department;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A tenant's employee record (PRD §14, §21). Distinct from {@code identity.AppUser}: an Employee
 * exists (and can be assigned a department/manager) from the moment an Admin creates it, while
 * {@code userId} stays {@code null} until the invite is accepted and stays a plain UUID column
 * rather than a JPA relation — {@code people} reads {@code identity} concepts by id only, never
 * navigates the association, so nothing here forces {@code AppUser} to load with an Employee.
 *
 * <p>No public setters (CLAUDE.md §10): every mutation is a named transition. Status and manager
 * changes are deliberately narrower than the general field patches ({@link #changeStatus} and
 * {@link #reassignManager}) — they are the only ways those two fields move, so {@link
 * EmployeeService} can guarantee a history row is written every time (PRD §6.3 FR-3.3/FR-3.4)
 * without relying on a caller to remember to do so.
 */
@Entity
@Table(
        name = "employee",
        uniqueConstraints =
                @UniqueConstraint(name = "employee_tenant_id_email_key", columnNames = {"tenant_id", "email"}))
public class Employee extends TenantAwareEntity {

    @Column(name = "user_id")
    private @Nullable UUID userId;

    @Column(name = "employee_code", length = 50)
    private @Nullable String employeeCode;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone", length = 30)
    private @Nullable String phone;

    @Column(name = "birth_date")
    private @Nullable LocalDate birthDate;

    @Column(name = "gender", length = 20)
    private @Nullable String gender;

    @Column(name = "marital_status", length = 20)
    private @Nullable String maritalStatus;

    @Column(name = "nationality", length = 100)
    private @Nullable String nationality;

    @Column(name = "citizenship", length = 100)
    private @Nullable String citizenship;

    @Column(name = "address_line1", length = 200)
    private @Nullable String addressLine1;

    @Column(name = "address_line2", length = 200)
    private @Nullable String addressLine2;

    @Column(name = "city", length = 100)
    private @Nullable String city;

    @Column(name = "country", length = 100)
    private @Nullable String country;

    @Column(name = "postal_code", length = 20)
    private @Nullable String postalCode;

    @Column(name = "hire_date")
    private @Nullable LocalDate hireDate;

    @Column(name = "termination_date")
    private @Nullable LocalDate terminationDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", length = 20)
    private @Nullable EmploymentType employmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmployeeStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private @Nullable Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private @Nullable Employee manager;

    @Column(name = "job_title", length = 150)
    private @Nullable String jobTitle;

    @Column(name = "work_location", length = 150)
    private @Nullable String workLocation;

    @Column(name = "working_hours_per_day", nullable = false)
    private BigDecimal workingHoursPerDay = BigDecimal.valueOf(8.0);

    @Column(name = "currency", length = 3)
    private @Nullable String currency;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "base_compensation_encrypted")
    private @Nullable String baseCompensation;

    protected Employee() {}

    private Employee(String firstName, String lastName, String email) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.status = EmployeeStatus.INVITED;
    }

    /**
     * Creates an Employee in {@link EmployeeStatus#INVITED}. Every other field starts empty and is
     * filled in by {@link #updateAdminFields}, mirroring the create form's minimal required set
     * (PRD §14.2) — everything past name/email is optional at creation time.
     */
    public static Employee create(String firstName, String lastName, String email) {
        return new Employee(firstName, lastName, email);
    }

    /** Links the login account created for this employee's invite. */
    public void linkUser(UUID userId) {
        this.userId = userId;
    }

    /**
     * Applies every field an Admin may set except status and manager (see class doc). Used both
     * for the initial post-create fill-in and for later Admin edits — there is no separate "create
     * details" method, since the set of fields is identical either way.
     */
    public void updateAdminFields(
            @Nullable String employeeCode,
            String firstName,
            String lastName,
            String email,
            @Nullable String phone,
            @Nullable LocalDate birthDate,
            @Nullable String gender,
            @Nullable String maritalStatus,
            @Nullable String nationality,
            @Nullable String citizenship,
            @Nullable LocalDate hireDate,
            @Nullable EmploymentType employmentType,
            @Nullable Department department,
            @Nullable String jobTitle,
            @Nullable String workLocation,
            BigDecimal workingHoursPerDay,
            @Nullable String currency,
            @Nullable String baseCompensation) {
        this.employeeCode = employeeCode;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.birthDate = birthDate;
        this.gender = gender;
        this.maritalStatus = maritalStatus;
        this.nationality = nationality;
        this.citizenship = citizenship;
        this.hireDate = hireDate;
        this.employmentType = employmentType;
        this.department = department;
        this.jobTitle = jobTitle;
        this.workLocation = workLocation;
        this.workingHoursPerDay = workingHoursPerDay;
        this.currency = currency;
        this.baseCompensation = baseCompensation;
    }

    /** The only fields an Employee may set on their own record (PRD §6.3 FR-3.10). */
    public void updateSelfServiceFields(
            @Nullable String phone,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String city,
            @Nullable String country,
            @Nullable String postalCode) {
        this.phone = phone;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.city = city;
        this.country = country;
        this.postalCode = postalCode;
    }

    /**
     * Moves the employee to a new status. Callers (only {@code EmployeeService}) are responsible
     * for writing the corresponding {@link EmployeeStatusHistory} row in the same transaction —
     * this method only changes the current-state column.
     */
    public void changeStatus(EmployeeStatus status) {
        this.status = status;
    }

    /**
     * Reassigns the manager. Callers (only {@code EmployeeService}) are responsible for writing the
     * corresponding {@link EmployeeManagerHistory} row in the same transaction.
     */
    public void reassignManager(@Nullable Employee manager) {
        this.manager = manager;
    }

    public void scheduleTermination(LocalDate terminationDate) {
        this.terminationDate = terminationDate;
    }

    public @Nullable UUID userId() {
        return userId;
    }

    public @Nullable String employeeCode() {
        return employeeCode;
    }

    public String firstName() {
        return firstName;
    }

    public String lastName() {
        return lastName;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public String email() {
        return email;
    }

    public @Nullable String phone() {
        return phone;
    }

    public @Nullable LocalDate birthDate() {
        return birthDate;
    }

    public @Nullable String gender() {
        return gender;
    }

    public @Nullable String maritalStatus() {
        return maritalStatus;
    }

    public @Nullable String nationality() {
        return nationality;
    }

    public @Nullable String citizenship() {
        return citizenship;
    }

    public @Nullable String addressLine1() {
        return addressLine1;
    }

    public @Nullable String addressLine2() {
        return addressLine2;
    }

    public @Nullable String city() {
        return city;
    }

    public @Nullable String country() {
        return country;
    }

    public @Nullable String postalCode() {
        return postalCode;
    }

    public @Nullable LocalDate hireDate() {
        return hireDate;
    }

    public @Nullable LocalDate terminationDate() {
        return terminationDate;
    }

    public @Nullable EmploymentType employmentType() {
        return employmentType;
    }

    public EmployeeStatus status() {
        return status;
    }

    public @Nullable Department department() {
        return department;
    }

    public @Nullable Employee manager() {
        return manager;
    }

    public @Nullable String jobTitle() {
        return jobTitle;
    }

    public @Nullable String workLocation() {
        return workLocation;
    }

    public BigDecimal workingHoursPerDay() {
        return workingHoursPerDay;
    }

    public @Nullable String currency() {
        return currency;
    }

    public @Nullable String baseCompensation() {
        return baseCompensation;
    }
}
