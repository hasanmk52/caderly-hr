/**
 * Team calendar grid and the per-user iCal feed (PRD §6.6, §9.4 US-CAL.3, sub-phase 1.8). A pure
 * read/projection layer over {@code timeoff}'s approved leave and {@code people}'s employee
 * records — no leave-domain logic of its own.
 *
 * <p>No entities of its own beyond the {@code ical_token} column added to {@code
 * identity.AppUser} (ADR 0014). Reads {@code timeoff} through {@code timeoff.TimeoffFacade} and
 * {@code people} through {@code people.PeopleFacade} (CLAUDE.md §4) — {@code calendar} never
 * touches either module's repositories directly.
 *
 * <p>{@code CalendarService.buildTeamCalendar} has a second consumer as of sub-phase 1.9 (ADR
 * 0015): {@code web.HomeController}'s "Time Off Today" widget calls it with {@code from == to ==
 * today} — the same "who's out in this range" query the team calendar grid runs, reused rather
 * than duplicated.
 */
@NullMarked
package com.caderly.caderlyhr.calendar;

import org.jspecify.annotations.NullMarked;
