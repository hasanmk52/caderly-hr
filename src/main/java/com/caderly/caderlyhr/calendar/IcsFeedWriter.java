package com.caderly.caderlyhr.calendar;

import com.caderly.caderlyhr.timeoff.TimeoffFacade.ApprovedLeaveEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Hand-rolled RFC 5545 writer — no dependency added (CLAUDE.md §12): the MVP surface is one
 * {@code VCALENDAR} of all-day {@code VEVENT}s, each needing only {@code UID}, {@code DTSTAMP},
 * {@code DTSTART}, {@code DTEND}, and {@code SUMMARY}. Pure and DB-free so it can be tested without
 * Spring.
 */
final class IcsFeedWriter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private IcsFeedWriter() {}

    static String write(List<ApprovedLeaveEntry> entries, Instant now) {
        String dtstamp = TIMESTAMP.format(now.atZone(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//Caderly//Team Calendar//EN\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        for (ApprovedLeaveEntry entry : entries) {
            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:").append(entry.leaveRequestId()).append("@caderly.app\r\n");
            sb.append("DTSTAMP:").append(dtstamp).append("\r\n");
            sb.append("DTSTART;VALUE=DATE:").append(formatDate(entry.startDate())).append("\r\n");
            // Exclusive end date per RFC 5545 §3.6.1 — an all-day event's DTEND is the day AFTER
            // the last day it covers, so a single-day leave request still renders as one day, not
            // zero.
            sb.append("DTEND;VALUE=DATE:").append(formatDate(entry.endDate().plusDays(1))).append("\r\n");
            sb.append("SUMMARY:").append(escape(entry.leaveTypeName())).append("\r\n");
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static String formatDate(LocalDate date) {
        return DATE.format(date);
    }

    /** RFC 5545 §3.3.11: backslash-escape backslash, semicolon, comma, and newline. */
    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n");
    }
}
