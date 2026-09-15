package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.identity.UserStatus;
import com.caderly.caderlyhr.notifications.EmailEvent;
import com.caderly.caderlyhr.notifications.EmailOutboxService;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three date-driven reminders PRD §17.2 hangs off employee data: birthday, work anniversary,
 * and expiring government ID. All three live in {@code people} because {@code people} owns the
 * dates; {@code notifications} cannot own them without depending back on this module and closing
 * a package cycle (CLAUDE.md §4).
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    /** PRD §17.2: "Document expiring ... 30/14/7 days before". */
    private static final List<Integer> EXPIRY_WINDOWS_DAYS = List.of(30, 14, 7);

    private final EmployeeRepository employees;
    private final GovernmentIdRepository governmentIds;
    private final AppUserRepository appUsers;
    private final EmailOutboxService emailOutbox;
    private final TenantFacade tenants;
    private final MessageSource messages;
    private final Clock clock;

    ReminderService(
            EmployeeRepository employees,
            GovernmentIdRepository governmentIds,
            AppUserRepository appUsers,
            EmailOutboxService emailOutbox,
            TenantFacade tenants,
            MessageSource messages,
            Clock clock) {
        this.employees = employees;
        this.governmentIds = governmentIds;
        this.appUsers = appUsers;
        this.emailOutbox = emailOutbox;
        this.tenants = tenants;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * Birthdays and work anniversaries falling on {@code today}, announced to the celebrant's team
     * — same department or same manager, the notion of "team" Home's My Peers widget already uses.
     * The celebrant is not told it is their own birthday.
     *
     * <p>A 29 February date only fires in a leap year. Picking 28 February or 1 March instead
     * would be a guess about which the employee considers "their" day, and both choices are wrong
     * for someone; three years in four with no email is the honest behaviour.
     */
    @Transactional
    public int sendCelebrationReminders(LocalDate today) {
        MonthDay todayMonthDay = MonthDay.from(today);
        Instant startOfToday = today.atStartOfDay(clock.getZone()).toInstant();
        String tenantName = tenants.currentBranding().name();
        int queued = 0;

        for (Employee celebrant : employees.findAllByStatusNot(EmployeeStatus.TERMINATED)) {
            if (matches(celebrant.birthDate(), todayMonthDay)) {
                queued +=
                        announce(
                                celebrant,
                                EmailEvent.BIRTHDAY,
                                Map.of("celebrantName", celebrant.fullName()),
                                startOfToday,
                                celebrant.fullName());
            }
            LocalDate hireDate = celebrant.hireDate();
            if (hireDate != null && matches(hireDate, todayMonthDay)) {
                int years = today.getYear() - hireDate.getYear();
                // Year zero is the hire date itself, not an anniversary.
                if (years >= 1) {
                    queued +=
                            announce(
                                    celebrant,
                                    EmailEvent.WORK_ANNIVERSARY,
                                    Map.of("celebrantName", celebrant.fullName(), "years", years),
                                    startOfToday,
                                    celebrant.fullName(),
                                    tenantName,
                                    years);
                }
            }
        }
        if (queued > 0) {
            log.info("Queued {} celebration reminder(s) for {}", queued, today);
        }
        return queued;
    }

    /**
     * Government IDs expiring exactly 30, 14 or 7 days from {@code today} (PRD FR-3.7, §17.2).
     * Goes to the employee and to every Admin — the employee has to renew it, the Admin has to
     * know before the employee's right to work lapses.
     *
     * <p>The model carries the ID type and country but never {@code id_number}: it is encrypted at
     * rest (ADR 0008) and an inbox is not somewhere to decrypt it into.
     */
    @Transactional
    public int sendDocumentExpiryReminders(LocalDate today) {
        Map<LocalDate, Integer> windowByDate = new LinkedHashMap<>();
        EXPIRY_WINDOWS_DAYS.forEach(days -> windowByDate.put(today.plusDays(days), days));

        List<GovernmentId> expiring = governmentIds.findAllByExpiryDateIn(windowByDate.keySet());
        if (expiring.isEmpty()) {
            return 0;
        }
        Instant startOfToday = today.atStartOfDay(clock.getZone()).toInstant();
        List<String> adminEmails = activeAdminEmails();
        int queued = 0;

        for (GovernmentId id : expiring) {
            Employee owner = id.employee();
            if (owner.status() == EmployeeStatus.TERMINATED) {
                continue;
            }
            LocalDate expiryDate = id.expiryDate();
            if (expiryDate == null) {
                continue;
            }
            int daysRemaining = windowByDate.get(expiryDate);
            String documentName = id.idType().label();
            Map<String, Object> model =
                    Map.of(
                            "employeeName", owner.fullName(),
                            "documentName", documentName,
                            "daysRemaining", daysRemaining,
                            "facts", expiryFacts(owner, id, expiryDate));

            for (String recipient : withAdmins(owner.email(), adminEmails)) {
                if (emailOutbox.alreadyQueuedSince(EmailEvent.DOCUMENT_EXPIRY, recipient, startOfToday)) {
                    continue;
                }
                if (emailOutbox
                        .enqueue(EmailEvent.DOCUMENT_EXPIRY, recipient, model, documentName, daysRemaining)
                        .isPresent()) {
                    queued++;
                }
            }
        }
        if (queued > 0) {
            log.info("Queued {} document-expiry reminder(s) for {}", queued, today);
        }
        return queued;
    }

    /** Queues one copy of a celebration mail to each of the celebrant's peers. */
    private int announce(
            Employee celebrant,
            EmailEvent event,
            Map<String, Object> model,
            Instant startOfToday,
            Object... subjectArgs) {
        UUID departmentId = celebrant.department() == null ? null : celebrant.department().requireId();
        UUID managerId = celebrant.manager() == null ? null : celebrant.manager().requireId();
        if (departmentId == null && managerId == null) {
            return 0;
        }
        int queued = 0;
        for (Employee peer : employees.findPeers(celebrant.requireId(), departmentId, managerId)) {
            if (emailOutbox.alreadyQueuedSince(event, peer.email(), startOfToday)) {
                continue;
            }
            if (emailOutbox.enqueue(event, peer.email(), model, subjectArgs).isPresent()) {
                queued++;
            }
        }
        return queued;
    }

    private Map<String, String> expiryFacts(Employee owner, GovernmentId id, LocalDate expiryDate) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put(message("email.field.employee"), owner.fullName());
        String country = id.country();
        facts.put(
                message("email.field.document"),
                country == null ? id.idType().label() : id.idType().label() + " (" + country + ")");
        facts.put(message("email.field.expires"), expiryDate.format(DATE_FMT));
        return facts;
    }

    /**
     * Admin addresses come from {@code app_user}, not from their Employee record: an Admin login
     * with no linked Employee (a bootstrap account) still needs to see an expiry warning, and
     * {@code PeopleFacade#listActiveAdminApprovalInfo} deliberately drops those.
     */
    private List<String> activeAdminEmails() {
        return appUsers.findAllByStatus(UserStatus.ACTIVE).stream()
                .filter(user -> user.roles().contains(Role.ADMIN))
                .map(AppUser::email)
                .toList();
    }

    private static List<String> withAdmins(String ownerEmail, List<String> adminEmails) {
        return Stream.concat(Stream.of(ownerEmail), adminEmails.stream())
                .distinct()
                .toList();
    }

    private static boolean matches(@Nullable LocalDate date, MonthDay today) {
        return date != null && MonthDay.from(date).equals(today);
    }

    private String message(String key) {
        return messages.getMessage(key, null, key, Locale.ENGLISH);
    }
}
