package com.caderly.caderlyhr.org;

import com.caderly.caderlyhr.audit.EntityAuditListener;
import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A department within exactly one {@link Division} (PRD §13.1). Tenant scoping is entirely
 * inherited from {@link TenantAwareEntity}.
 *
 * <p>No public setters (CLAUDE.md §10): every mutation is a named transition.
 */
@Entity
@EntityListeners(EntityAuditListener.class)
@Table(
        name = "department",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "department_tenant_id_name_key",
                        columnNames = {"tenant_id", "name"}))
public class Department extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description")
    private @Nullable String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "division_id", nullable = false)
    private Division division;

    // Plain UUID, not a JPA relationship: org must not depend on people.Employee directly
    // (CLAUDE.md §4). The DB-level FK exists (V202608121242); resolving/validating the id is the
    // caller's job via people.PeopleFacade.
    @Column(name = "head_employee_id")
    private @Nullable UUID headEmployeeId;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    protected Department() {}

    private Department(String name, @Nullable String description, Division division) {
        this.name = name;
        this.description = description;
        this.division = division;
    }

    public static Department create(String name, @Nullable String description, Division division) {
        return new Department(name, description, division);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void updateDescription(@Nullable String description) {
        this.description = description;
    }

    public void moveToDivision(Division division) {
        this.division = division;
    }

    /**
     * PRD §13.2: hard delete only when nothing references the row; otherwise archive. Called by
     * {@code DepartmentService} once its employee-count guard finds this Department still has
     * active employees.
     */
    public void archive() {
        this.archived = true;
    }

    public String name() {
        return name;
    }

    public @Nullable String description() {
        return description;
    }

    public Division division() {
        return division;
    }

    public @Nullable UUID headEmployeeId() {
        return headEmployeeId;
    }

    public boolean archived() {
        return archived;
    }
}
