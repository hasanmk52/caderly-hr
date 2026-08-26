/**
 * Company-wide files and per-employee documents (PRD §6.7, FR-3.9, §21). Every entity here is
 * tenant-scoped (CLAUDE.md §5). {@code employeeId} on {@link
 * com.caderly.caderlyhr.documents.EmployeeDocument} is a plain id, not a JPA relation — {@code
 * documents} reads no {@code people} data at all, so there is nothing to go through a facade for
 * (unlike {@code timeoff}, which reads {@code people.PeopleFacade} for hire dates and approval
 * routing).
 *
 * <p>File bytes live in {@code storage.FileStorage}, never here (CLAUDE.md §11) — these entities
 * are metadata only.
 */
@NullMarked
package com.caderly.caderlyhr.documents;

import org.jspecify.annotations.NullMarked;
