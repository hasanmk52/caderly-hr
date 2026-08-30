package com.caderly.caderlyhr.calendar;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.common.SecureToken;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the per-user iCal feed token's lifecycle (PRD FR-6.5, ADR 0014). Lazy get-or-create:
 * nothing is generated until a user's first visit to Settings -> Calendar integration.
 */
@Service
public class CalendarTokenService {

    private static final Logger log = LoggerFactory.getLogger(CalendarTokenService.class);

    private final AppUserRepository users;

    CalendarTokenService(AppUserRepository users) {
        this.users = users;
    }

    /** Returns the user's existing token, generating one on first call. */
    @Transactional
    public String getOrCreateToken(UUID userId) {
        AppUser user = requireUser(userId);
        String existing = user.icalToken();
        if (existing != null) {
            return existing;
        }
        String token = SecureToken.generate();
        user.issueIcalToken(token);
        users.save(user);
        log.info("Issued iCal token for user {}", userId);
        return token;
    }

    /** Forces a fresh token, invalidating whatever URL was issued before (Settings "Regenerate"). */
    @Transactional
    public String regenerateToken(UUID userId) {
        AppUser user = requireUser(userId);
        String token = SecureToken.generate();
        user.issueIcalToken(token);
        users.save(user);
        log.info("Regenerated iCal token for user {}", userId);
        return token;
    }

    private AppUser requireUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
    }
}
