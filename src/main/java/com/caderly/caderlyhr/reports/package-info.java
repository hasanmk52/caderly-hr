/**
 * Admin-only reporting (PRD §16, Phase 1.12): Leave Balance, Leave Utilization, and Headcount.
 * No entities of its own — every report reads already-aggregated data through {@code
 * people.PeopleFacade} and {@code timeoff.TimeoffFacade}, joining/filtering it in Java. Nothing
 * in this package writes anything, and nothing may depend back on it (CLAUDE.md §4).
 */
@NullMarked
package com.caderly.caderlyhr.reports;

import org.jspecify.annotations.NullMarked;
