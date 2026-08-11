package com.helyx.helyxhr.org;

import com.helyx.helyxhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    // No FK yet: `employee` doesn't exist until Phase 1.4 (see CURRENT_PHASE.md). Plain UUID,
    // never populated in 1.3 — reserved so the 1.4 migration only needs to ADD CONSTRAINT.
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
     * PRD §13.2: hard delete only when nothing references the row; otherwise archive. In Phase
     * 1.3 nothing can yet reference a Department (no Employee entity), so this is only reachable
     * once 1.4 wires the employee-count guard into {@code DepartmentService}.
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
