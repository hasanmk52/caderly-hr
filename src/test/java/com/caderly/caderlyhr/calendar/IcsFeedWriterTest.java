package com.caderly.caderlyhr.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.timeoff.TimeoffFacade.ApprovedLeaveEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure RFC 5545 formatting — no Spring, no database. */
class IcsFeedWriterTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:34:56Z");

    @Test
    void write_withNoEntries_producesAnEmptyButValidCalendar() {
        String ics = IcsFeedWriter.write(List.of(), NOW);

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n");
        assertThat(ics).contains("VERSION:2.0\r\n");
        assertThat(ics).endsWith("END:VCALENDAR\r\n");
        assertThat(ics).doesNotContain("BEGIN:VEVENT");
    }

    @Test
    void write_withOneEntry_emitsRequiredVeventFields() {
        UUID requestId = UUID.randomUUID();
        ApprovedLeaveEntry entry =
                new ApprovedLeaveEntry(
                        UUID.randomUUID(),
                        requestId,
                        "Vacation",
                        "#0d6efd",
                        "bi-airplane",
                        LocalDate.of(2026, 9, 10),
                        LocalDate.of(2026, 9, 12),
                        false,
                        false,
                        new BigDecimal("3.00"));

        String ics = IcsFeedWriter.write(List.of(entry), NOW);

        assertThat(ics).contains("BEGIN:VEVENT\r\n");
        assertThat(ics).contains("UID:" + requestId + "@caderly.app\r\n");
        assertThat(ics).contains("DTSTAMP:20260830T123456Z\r\n");
        assertThat(ics).contains("DTSTART;VALUE=DATE:20260910\r\n");
        // Exclusive end (RFC 5545 §3.6.1): the day AFTER the last covered day.
        assertThat(ics).contains("DTEND;VALUE=DATE:20260913\r\n");
        assertThat(ics).contains("SUMMARY:Vacation\r\n");
        assertThat(ics).contains("END:VEVENT\r\n");
    }

    @Test
    void write_withSingleDayLeave_endDateIsTheDayAfterStart() {
        ApprovedLeaveEntry entry =
                new ApprovedLeaveEntry(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Sick",
                        null,
                        null,
                        LocalDate.of(2026, 9, 10),
                        LocalDate.of(2026, 9, 10),
                        false,
                        false,
                        new BigDecimal("1.00"));

        String ics = IcsFeedWriter.write(List.of(entry), NOW);

        assertThat(ics).contains("DTSTART;VALUE=DATE:20260910\r\n");
        assertThat(ics).contains("DTEND;VALUE=DATE:20260911\r\n");
    }

    @Test
    void write_withLeaveTypeNameContainingSpecialCharacters_escapesThem() {
        ApprovedLeaveEntry entry =
                new ApprovedLeaveEntry(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Comp, Time; Off",
                        null,
                        null,
                        LocalDate.of(2026, 9, 10),
                        LocalDate.of(2026, 9, 10),
                        false,
                        false,
                        new BigDecimal("1.00"));

        String ics = IcsFeedWriter.write(List.of(entry), NOW);

        assertThat(ics).contains("SUMMARY:Comp\\, Time\\; Off\r\n");
    }

    @Test
    void write_withMultipleEntries_emitsOneVeventEach() {
        ApprovedLeaveEntry first =
                new ApprovedLeaveEntry(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Vacation",
                        null,
                        null,
                        LocalDate.of(2026, 9, 10),
                        LocalDate.of(2026, 9, 10),
                        false,
                        false,
                        new BigDecimal("1.00"));
        ApprovedLeaveEntry second =
                new ApprovedLeaveEntry(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Sick",
                        null,
                        null,
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 1),
                        false,
                        false,
                        new BigDecimal("1.00"));

        String ics = IcsFeedWriter.write(List.of(first, second), NOW);

        assertThat(ics.split("BEGIN:VEVENT", -1)).hasSize(3); // 2 events + the text before the first
    }
}
