/**
 * Employee records and their sub-entities: education, emergency contacts, government IDs, bank
 * details, benefits, and the append-only status/manager history tables (PRD §14, §21).
 *
 * <p>Every entity here is tenant-scoped (CLAUDE.md §5). Reads of {@code org}'s Division/
 * Department go through {@code org.OrgFacade}; {@code people} exposes its own {@code
 * PeopleFacade} for the one thing {@code org}'s Department-delete flow needs — an employee
 * count — but that facade is consumed by {@code web}, never by {@code org} itself, so the two
 * domain packages stay acyclic (see {@code AdminOrganizationController}).
 */
@NullMarked
package com.helyx.helyxhr.people;

import org.jspecify.annotations.NullMarked;
