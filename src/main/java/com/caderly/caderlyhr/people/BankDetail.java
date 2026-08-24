package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.CryptoConverter;
import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jspecify.annotations.Nullable;

/**
 * One bank-detail row per employee (PRD §6.3 FR-3.8, §21 unique constraint). Every value except
 * the bank name and SWIFT/BIC is encrypted at rest (CLAUDE.md §6 A02, ADR 0008); visible only to
 * Admin + the owning employee (enforced in {@code EmployeeService}, not here).
 */
@Entity
@Table(
        name = "bank_detail",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "bank_detail_tenant_id_employee_id_key",
                        columnNames = {"tenant_id", "employee_id"}))
public class BankDetail extends TenantAwareEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "bank_name", length = 200)
    private @Nullable String bankName;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "account_name_encrypted")
    private @Nullable String accountName;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "account_number_encrypted")
    private @Nullable String accountNumber;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "iban_encrypted")
    private @Nullable String iban;

    @Column(name = "swift_bic", length = 20)
    private @Nullable String swiftBic;

    protected BankDetail() {}

    private BankDetail(Employee employee) {
        this.employee = employee;
    }

    public static BankDetail create(Employee employee) {
        return new BankDetail(employee);
    }

    public void update(
            @Nullable String bankName,
            @Nullable String accountName,
            @Nullable String accountNumber,
            @Nullable String iban,
            @Nullable String swiftBic) {
        this.bankName = bankName;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.iban = iban;
        this.swiftBic = swiftBic;
    }

    public Employee employee() {
        return employee;
    }

    public @Nullable String bankName() {
        return bankName;
    }

    public @Nullable String accountName() {
        return accountName;
    }

    public @Nullable String accountNumber() {
        return accountNumber;
    }

    public @Nullable String iban() {
        return iban;
    }

    public @Nullable String swiftBic() {
        return swiftBic;
    }
}
