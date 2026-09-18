package com.caderly.caderlyhr.timeoff;

import com.caderly.caderlyhr.notifications.EmailEvent;
import com.caderly.caderlyhr.notifications.EmailOutboxService;
import com.caderly.caderlyhr.people.PeopleFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD §17.2's "Public holiday tomorrow → all active users". Lives in {@code timeoff} because
 * {@code timeoff} owns {@link PublicHoliday}; {@code notifications} could not own it without
 * depending back on this module and closing a package cycle (CLAUDE.md §4).
 */
@Service
public class HolidayReminderService {

    private static final Logger log = LoggerFactory.getLogger(HolidayReminderService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH);

    private final PublicHolidayRepository holidays;
    private final PeopleFacade people;
    private final EmailOutboxService emailOutbox;
    private final Clock clock;

    HolidayReminderService(
            PublicHolidayRepository holidays,
            PeopleFacade people,
            EmailOutboxService emailOutbox,
            Clock clock) {
        this.holidays = holidays;
        this.people = people;
        this.emailOutbox = emailOutbox;
        this.clock = clock;
    }

    /**
     * Queues one reminder per active employee for every holiday falling on {@code holidayDate}.
     *
     * @return how many emails were queued — zero when there is no holiday, when the tenant has the
     *     category switched off, or when today's sweep already ran
     */
    @Transactional
    public int remindAbout(LocalDate holidayDate) {
        List<PublicHoliday> due = holidays.findAllByDate(holidayDate);
        if (due.isEmpty()) {
            return 0;
        }
        Instant startOfToday = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        List<PeopleFacade.EmployeeContact> recipients = people.listActiveEmployeeContacts();
        String formattedDate = holidayDate.format(DATE_FMT);

        int queued = 0;
        for (PublicHoliday holiday : due) {
            Map<String, Object> model =
                    Map.of("holidayName", holiday.name(), "holidayDate", formattedDate);
            for (PeopleFacade.EmployeeContact recipient : recipients) {
                // A second run on the same calendar day is a restart, not a second holiday.
                if (emailOutbox.alreadyQueuedSince(
                        EmailEvent.HOLIDAY_REMINDER, recipient.email(), startOfToday)) {
                    continue;
                }
                if (emailOutbox
                        .enqueue(EmailEvent.HOLIDAY_REMINDER, recipient.email(), model, holiday.name())
                        .isPresent()) {
                    queued++;
                }
            }
        }
        if (queued > 0) {
            log.info("Queued {} holiday reminder(s) for {}", queued, holidayDate);
        }
        return queued;
    }
}
