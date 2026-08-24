package com.caderly.caderlyhr.org;

import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jspecify.annotations.Nullable;

/**
 * A top-level organizational grouping (PRD §13.1) — e.g. "Engineering", "Sales". Every {@link
 * Department} belongs to exactly one Division. Tenant scoping is entirely inherited from {@link
 * TenantAwareEntity}.
 *
 * <p>No public setters (CLAUDE.md §10): every mutation is a named transition.
 */
@Entity
@Table(
        name = "division",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "division_tenant_id_name_key",
                        columnNames = {"tenant_id", "name"}))
public class Division extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    protected Division() {}

    private Division(String name, @Nullable String description) {
        this.name = name;
        this.description = description;
    }

    public static Division create(String name, @Nullable String description) {
        return new Division(name, description);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void updateDescription(@Nullable String description) {
        this.description = description;
    }

    /** PRD §13.2: hard delete only when nothing references the row; otherwise archive. */
    public void archive() {
        this.archived = true;
    }

    public String name() {
        return name;
    }

    public @Nullable String description() {
        return description;
    }

    public boolean archived() {
        return archived;
    }
}
