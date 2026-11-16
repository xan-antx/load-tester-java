package com.elevate.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Formats times as the Python version stored them (SQLite's {@code CURRENT_TIMESTAMP}):
 * UTC, {@code "2026-11-16 10:15:00"}. The frontend appends the {@code Z} itself.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    /** The formatted time, or {@code null}. */
    public static String format(Instant instant) {
        return instant == null ? null : FORMAT.format(instant);
    }
}
