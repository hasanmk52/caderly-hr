package com.caderly.caderlyhr.signup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly purge of expired sign-ups (ADR 0022 decision E): releases held addresses and keeps
 * unconfirmed personal data no longer than a day and an hour.
 */
@Component
class PendingSignupCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PendingSignupCleanupJob.class);

    private final SignupService signups;
    private final boolean enabled;

    PendingSignupCleanupJob(
            SignupService signups, @Value("${caderly.signup.cleanup-job.enabled:true}") boolean enabled) {
        this.signups = signups;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${caderly.signup.cleanup-job.cron:0 15 * * * *}")
    void scheduledRun() {
        if (!enabled) {
            return;
        }
        int removed = signups.purgeExpired();
        if (removed > 0) {
            log.info("Purged {} expired sign-up(s)", removed);
        }
    }
}
