package com.caderly.caderlyhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.ValidationException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure logic, no Spring context (CLAUDE.md §8: "critical logic, TDD first"). Implements PRD
 * §12.3's pseudocode exactly.
 */
class LeaveDurationCalculatorTest {

    private static final Set<DayOfWeek> SAT_SUN = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    @Test
    void compute_fullWeekMondayToFriday_returnsFiveDays() {
        BigDecimal duration =
                LeaveDurationCalculator.compute(
                        LocalDate.of(2026, 8, 17), // Monday
                        LocalDate.of(2026, 8, 21), // Friday
                        false,
                        false,
                        SAT_SUN,
                        Set.of());
        assertThat(duration).isEqualByComparingTo("5.00");
    }

    @Test
    void compute_rangeSpanningAWeekend_skipsWeekendDays() {
        BigDecimal duration =
                LeaveDurationCalculator.compute(
                        LocalDate.of(2026, 8, 17), // Monday
                        LocalDate.of(2026, 8, 24), // next Monday
                        false,
                        false,
                        SAT_SUN,
                        Set.of());
        // Mon-Fri (5) + Sat/Sun skipped + Mon (1) = 6
        assertThat(duration).isEqualByComparingTo("6.00");
    }

    @Test
    void compute_rangeContainingAHoliday_skipsHolidayDay() {
        LocalDate holiday = LocalDate.of(2026, 8, 19); // Wednesday
        BigDecimal duration =
                LeaveDurationCalculator.compute(
                        LocalDate.of(2026, 8, 17), // Monday
                        LocalDate.of(2026, 8, 21), // Friday
                        false,
                        false,
                        SAT_SUN,
                        Set.of(holiday));
        assertThat(duration).isEqualByComparingTo("4.00");
    }

    @Test
    void compute_holidayFallingOnAWeekend_doesNotDoubleSubtract() {
        LocalDate saturdayHoliday = LocalDate.of(2026, 8, 22); // Saturday
        BigDecimal duration =
                LeaveDurationCalculator.compute(
                        LocalDate.of(2026, 8, 17), // Monday
                        LocalDate.of(2026, 8, 23), // Sunday
                        false,
                        false,
                        SAT_SUN,
                        Set.of(saturdayHoliday));
        assertThat(duration).isEqualByComparingTo("5.00");
    }

    @Test
    void compute_sameDayNoHalfDayFlags_returnsOneFullDay() {
        LocalDate day = LocalDate.of(2026, 8, 17);
        BigDecimal duration = LeaveDurationCalculator.compute(day, day, false, false, SAT_SUN, Set.of());
        assertThat(duration).isEqualByComparingTo("1.00");
    }

    @Test
    void compute_sameDayStartHalfPm_returnsHalfDay() {
        LocalDate day = LocalDate.of(2026, 8, 17);
        BigDecimal duration = LeaveDurationCalculator.compute(day, day, true, false, SAT_SUN, Set.of());
        assertThat(duration).isEqualByComparingTo("0.50");
    }

    @Test
    void compute_sameDayEndHalfAm_returnsHalfDay() {
        LocalDate day = LocalDate.of(2026, 8, 17);
        BigDecimal duration = LeaveDurationCalculator.compute(day, day, false, true, SAT_SUN, Set.of());
        assertThat(duration).isEqualByComparingTo("0.50");
    }

    @Test
    void compute_sameDayBothHalfFlagsSet_throwsValidationException() {
        LocalDate day = LocalDate.of(2026, 8, 17);
        assertThatThrownBy(() -> LeaveDurationCalculator.compute(day, day, true, true, SAT_SUN, Set.of()))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_INVALID_HALF_DAY_COMBINATION");
    }

    @Test
    void compute_halfDayAppliedOnlyAtBoundaries_notMidRange() {
        // Mon startHalfPM (0.5) + Tue full (1.0) + Wed full (1.0) = 2.5. The half-day flags never
        // apply to Tuesday/Wednesday even though they sit "between" start and end.
        BigDecimal duration =
                LeaveDurationCalculator.compute(
                        LocalDate.of(2026, 8, 17), // Monday
                        LocalDate.of(2026, 8, 19), // Wednesday
                        true,
                        false,
                        SAT_SUN,
                        Set.of());
        assertThat(duration).isEqualByComparingTo("2.50");
    }

    @Test
    void compute_endHalfAmAtEndBoundary_appliesOnlyToEndDay() {
        // Mon full (1.0) + Tue full (1.0) + Wed endHalfAM (0.5) = 2.5.
        BigDecimal duration =
                LeaveDurationCalculator.compute(
                        LocalDate.of(2026, 8, 17), // Monday
                        LocalDate.of(2026, 8, 19), // Wednesday
                        false,
                        true,
                        SAT_SUN,
                        Set.of());
        assertThat(duration).isEqualByComparingTo("2.50");
    }

    @Test
    void compute_yearBoundaryRange_countsAcrossTheBoundary() {
        BigDecimal duration =
                LeaveDurationCalculator.compute(
                        LocalDate.of(2026, 12, 30), // Wednesday
                        LocalDate.of(2027, 1, 2), // Saturday
                        false,
                        false,
                        SAT_SUN,
                        Set.of());
        // Wed, Thu, Fri (3) + Sat skipped = 3
        assertThat(duration).isEqualByComparingTo("3.00");
    }

    @Test
    void compute_startAfterEnd_throwsValidationException() {
        LocalDate start = LocalDate.of(2026, 8, 21);
        LocalDate end = LocalDate.of(2026, 8, 17);
        assertThatThrownBy(() -> LeaveDurationCalculator.compute(start, end, false, false, SAT_SUN, Set.of()))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_INVALID_DATE_RANGE");
    }

    @Test
    void decodeWeekend_defaultBitmask96_returnsSaturdayAndSunday() {
        assertThat(LeaveDurationCalculator.decodeWeekend(96)).containsExactlyInAnyOrder(
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    void decodeWeekend_customFridaySaturdayBitmask_returnsFridayAndSaturday() {
        // Fri=16, Sat=32 -> 48 (bit = 1 << (DayOfWeek.getValue() - 1), Mon=1..Sun=7)
        assertThat(LeaveDurationCalculator.decodeWeekend(48)).containsExactlyInAnyOrder(
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
    }
}
