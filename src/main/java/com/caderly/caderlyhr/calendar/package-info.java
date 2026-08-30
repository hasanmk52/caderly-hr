/**
 * Team calendar grid and the per-user iCal feed (PRD §6.6, §9.4 US-CAL.3, sub-phase 1.8). A pure
 * read/projection layer over {@code timeoff}'s approved leave and {@code people}'s employee
 * records — no leave-domain logic of its own.
 *
 * <p>No entities of its own beyond the {@code ical_token} column added to {@code
 * identity.AppUser} (ADR 0014). Reads {@code timeoff} through {@code timeoff.TimeoffFacade} and
 * {@code people} through {@code people.PeopleFacade} (CLAUDE.md §4) — {@code calendar} never
 * touches either module's repositories directly.
 */
@NullMarked
package com.caderly.caderlyhr.calendar;

import org.jspecify.annotations.NullMarked;
