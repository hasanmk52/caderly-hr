package com.caderly.caderlyhr.documents;

/** Who besides an Admin may see an {@link EmployeeDocument} (PRD FR-3.9). */
public enum DocumentVisibility {
    /** The owning employee and any Admin. */
    EMPLOYEE_PRIVATE,
    /** Admin only — including the owning employee's own account. */
    ADMIN_ONLY;

    /** Display label for UI dropdowns and read views. */
    public String label() {
        return switch (this) {
            case EMPLOYEE_PRIVATE -> "Employee & Admin";
            case ADMIN_ONLY -> "Admin only";
        };
    }
}
