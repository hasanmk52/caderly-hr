package com.caderly.caderlyhr.documents;

/** Who besides an Admin may see an {@link EmployeeDocument} (PRD FR-3.9). */
public enum DocumentVisibility {
    /** The owning employee and any Admin. */
    EMPLOYEE_PRIVATE,
    /** Admin only — including the owning employee's own account. */
    ADMIN_ONLY
}
