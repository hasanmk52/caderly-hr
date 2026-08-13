package com.helyx.helyxhr.people;

/** PRD §6.3 FR-3.1. */
public enum EmploymentType {
    FULL_TIME,
    PART_TIME,
    CONTRACT,
    INTERN;

    /** Display label for UI dropdowns and read views (PRD §6.3 FR-3.1 wording). */
    public String label() {
        return switch (this) {
            case FULL_TIME -> "Full-Time";
            case PART_TIME -> "Part-Time";
            case CONTRACT -> "Contract";
            case INTERN -> "Intern";
        };
    }
}
