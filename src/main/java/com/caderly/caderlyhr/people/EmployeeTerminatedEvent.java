package com.caderly.caderlyhr.people;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published after an {@link Employee} is terminated (PRD §14.4: "cancel future leave requests").
 * An event, not a direct call, for the same reason as {@link EmployeeHiredEvent}: {@code people}
 * must not gain a dependency on {@code timeoff} (see that record's doc comment; ADR 0009).
 */
public record EmployeeTerminatedEvent(UUID employeeId, LocalDate effectiveDate) {}
