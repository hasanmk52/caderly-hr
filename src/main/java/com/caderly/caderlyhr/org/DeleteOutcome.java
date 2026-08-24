package com.caderly.caderlyhr.org;

/**
 * What actually happened when an Admin asked to delete a Division or Department (PRD §13.2):
 * hard delete when nothing references the row, otherwise archived instead. Both outcomes remove
 * the row from the default (non-archived) list, so the UI only needs this to pick the toast
 * wording.
 */
public enum DeleteOutcome {
    DELETED,
    ARCHIVED
}
