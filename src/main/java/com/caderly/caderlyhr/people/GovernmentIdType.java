package com.caderly.caderlyhr.people;

/** PRD §6.3 FR-3.7. */
public enum GovernmentIdType {
    PASSPORT,
    NATIONAL_ID,
    VISA,
    EMIRATES_ID,
    OTHER;

    /** Display label for UI dropdowns and read views. */
    public String label() {
        return switch (this) {
            case PASSPORT -> "Passport";
            case NATIONAL_ID -> "National ID";
            case VISA -> "Visa";
            case EMIRATES_ID -> "Emirates ID";
            case OTHER -> "Other";
        };
    }
}
