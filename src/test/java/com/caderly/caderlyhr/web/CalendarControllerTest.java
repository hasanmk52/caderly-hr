package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;
import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;

import com.caderly.caderlyhr.web.CalendarController.Segment;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure date-math for splitting a leave bar's date range at weekend boundaries (no Spring context
 * needed) — the bar must stop covering weekend cells so their grey shading stays visible.
 */
class CalendarControllerTest {

    private static final Set<DayOfWeek> DEFAULT_WEEKEND = Set.of(SATURDAY, SUNDAY);

    @Test
    void splitIntoWorkingSegments_whenRangeEntirelyWithinWorkingWeek_returnsOneUnchangedSegment() {
        LocalDate monday = LocalDate.of(2026, 9, 7);
        LocalDate wednesday = LocalDate.of(2026, 9, 9);

        var segments = CalendarController.splitIntoWorkingSegments(monday, wednesday, DEFAULT_WEEKEND);

        assertThat(segments).containsExactly(new Segment(monday, wednesday));
    }

    @Test
    void splitIntoWorkingSegments_whenRangeSpansOneWeekend_splitsAroundIt() {
        LocalDate friday = LocalDate.of(2026, 9, 4);
        LocalDate monday = LocalDate.of(2026, 9, 7);

        var segments = CalendarController.splitIntoWorkingSegments(friday, monday, DEFAULT_WEEKEND);

        assertThat(segments).containsExactly(new Segment(friday, friday), new Segment(monday, monday));
    }

    @Test
    void splitIntoWorkingSegments_whenRangeSpansTwoWeekends_returnsThreeSegments() {
        // Mon 2026-08-31 through Mon 2026-09-14: two full weekends inside the range.
        LocalDate start = LocalDate.of(2026, 8, 31);
        LocalDate end = LocalDate.of(2026, 9, 14);

        var segments = CalendarController.splitIntoWorkingSegments(start, end, DEFAULT_WEEKEND);

        assertThat(segments)
                .containsExactly(
                        new Segment(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 4)),
                        new Segment(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11)),
                        new Segment(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 14)));
    }

    @Test
    void splitIntoWorkingSegments_whenRangeIsOnlyWeekendDays_returnsEmptyList() {
        LocalDate saturday = LocalDate.of(2026, 9, 5);
        LocalDate sunday = LocalDate.of(2026, 9, 6);

        var segments = CalendarController.splitIntoWorkingSegments(saturday, sunday, DEFAULT_WEEKEND);

        assertThat(segments).isEmpty();
    }

    @Test
    void splitIntoWorkingSegments_usesTheGivenWeekendSetNotHardcodedSatSun() {
        LocalDate friday = LocalDate.of(2026, 9, 4);
        LocalDate monday = LocalDate.of(2026, 9, 7);
        Set<DayOfWeek> fridaySaturdayWeekend = Set.of(FRIDAY, SATURDAY);

        var segments =
                CalendarController.splitIntoWorkingSegments(friday, monday, fridaySaturdayWeekend);

        // Friday is now a weekend day too, so only Sunday-Monday remain as the working run.
        assertThat(segments)
                .containsExactly(new Segment(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 7)));
    }
}
