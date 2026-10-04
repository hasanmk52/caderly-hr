package com.caderly.caderlyhr.identity;

import java.util.Arrays;
import java.util.Optional;

/**
 * The single whitelist of Home dashboard widgets, declared in default order (ADR 0021). Stored
 * layouts only ever reference {@link #key()}s from here, so a stale or tampered value can never
 * reach a template or an {@code hx-get} URL.
 */
public enum DashboardWidget {
    BOOK_TIME_OFF("book-time-off", "home.book-time-off-heading"),
    MY_PEERS("my-peers", "home.widget.my-peers.heading"),
    TIME_OFF_TODAY("time-off-today", "home.widget.time-off-today.heading"),
    MY_DAYS_OFF("my-days-off", "home.widget.my-days-off.heading"),
    UPCOMING_HOLIDAYS("upcoming-holidays", "home.widget.upcoming-holidays.heading"),
    RESOURCES("resources", "home.widget.resources.heading");

    private final String key;
    private final String titleKey;

    DashboardWidget(String key, String titleKey) {
        this.key = key;
        this.titleKey = titleKey;
    }

    public String key() {
        return key;
    }

    /** The htmx fragment endpoint that loads this widget. */
    public String path() {
        return "/widgets/" + key;
    }

    public String titleKey() {
        return titleKey;
    }

    public static Optional<DashboardWidget> fromKey(String key) {
        return Arrays.stream(values()).filter(w -> w.key.equals(key)).findFirst();
    }
}
