package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.ValidationException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Implements PRD §12.3's duration pseudocode exactly. Pure and static — zero DI — so it stays
 * trivially unit-testable (CLAUDE.md §8: "critical logic, TDD first"); callers ({@code
 * LeaveRequestService}) resolve the weekend/holiday sets from {@code TenantFacade} and {@code
 * PublicHolidayRepository} before calling in.
 */
final class LeaveDurationCalculator {

    private LeaveDurationCalculator() {}

    static BigDecimal compute(
            LocalDate start,
            LocalDate end,
            boolean startHalfPm,
            boolean endHalfAm,
            Set<DayOfWeek> weekend,
            Set<LocalDate> holidays) {
        if (start.isAfter(end)) {
            throw new ValidationException(
                    "LEAVE_INVALID_DATE_RANGE", "Start date must not be after end date");
        }
        if (start.equals(end) && startHalfPm && endHalfAm) {
            throw new ValidationException(
                    "LEAVE_INVALID_HALF_DAY_COMBINATION",
                    "A single-day request cannot be half-day AM and half-day PM at the same time");
        }

        BigDecimal days = BigDecimal.ZERO;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (weekend.contains(d.getDayOfWeek()) || holidays.contains(d)) {
                continue;
            }
            if (d.equals(start) && d.equals(end)) {
                if (startHalfPm || endHalfAm) {
                    days = days.add(new BigDecimal("0.5"));
                } else {
                    days = days.add(BigDecimal.ONE);
                }
            } else if (d.equals(start) && startHalfPm) {
                days = days.add(new BigDecimal("0.5"));
            } else if (d.equals(end) && endHalfAm) {
                days = days.add(new BigDecimal("0.5"));
            } else {
                days = days.add(BigDecimal.ONE);
            }
        }
        return days.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /**
     * Decodes {@code Tenant.weekendDays}' bitmask into the set of weekend {@link DayOfWeek}s.
     * Convention: {@code bit = 1 << (DayOfWeek.getValue() - 1)}, i.e. Mon=1..Sun=7 per {@code
     * java.time} — Mon=1,Tue=2,Wed=4,Thu=8,Fri=16,Sat=32,Sun=64. The stored default of 96 (=32+64)
     * decodes to Sat+Sun either way the two bits in the field's stale "Sat=64,Sun=32" comment are
     * read, since nothing consumed this bitmask before this phase.
     */
    static Set<DayOfWeek> decodeWeekend(int bitmask) {
        Set<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            int bit = 1 << (day.getValue() - 1);
            if ((bitmask & bit) != 0) {
                result.add(day);
            }
        }
        return result;
    }
}
