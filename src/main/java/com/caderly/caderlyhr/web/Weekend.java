package com.caderly.caderlyhr.web;

import java.util.Locale;

/**
 * The weekend choices sign-up offers, as {@code tenant.weekend_days} bitmasks (Mon=1 … Sat=32,
 * Sun=64 — {@code timeoff.LeaveDurationCalculator#decodeWeekend}). Admins can set any other
 * combination later in Settings.
 */
public enum Weekend {
    SAT_SUN(32 | 64),
    FRI_SAT(16 | 32),
    FRI(16),
    SUN(64);

    private final int bitmask;

    Weekend(int bitmask) {
        this.bitmask = bitmask;
    }

    public int bitmask() {
        return bitmask;
    }

    public String messageKey() {
        return "site.signup.weekend." + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
