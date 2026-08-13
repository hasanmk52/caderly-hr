package com.helyx.helyxhr.identity;

/**
 * Tenant-scoped roles (PRD §5, §26). Ordered least- to most-privileged; the {@code ADMIN >
 * MANAGER > EMPLOYEE} hierarchy itself is configured once as a Spring {@code RoleHierarchy} bean
 * in {@code SecurityConfig}, so {@code @PreAuthorize} never has to spell out implied roles.
 *
 * <p>Super Admin is deliberately absent: it is a separate security realm over the cross-tenant
 * {@code super_admin} table, not a role a tenant user can hold (PRD §26, Phase 1.13).
 */
public enum Role {
    EMPLOYEE,
    MANAGER,
    ADMIN;

    /** Spring Security authority name for this role, i.e. the {@code ROLE_} prefixed form. */
    public String authority() {
        return "ROLE_" + name();
    }

    /** Display label for UI dropdowns, checkboxes, and read views. */
    public String label() {
        return switch (this) {
            case EMPLOYEE -> "Employee";
            case MANAGER -> "Manager";
            case ADMIN -> "Admin";
        };
    }
}
