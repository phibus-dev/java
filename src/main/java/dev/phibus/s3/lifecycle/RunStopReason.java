package dev.phibus.s3.lifecycle;

import java.util.Locale;

public enum RunStopReason {
    NORMAL,
    TIMEOUT,
    USER_CANCELLED,
    DEADLINE_EXCEEDED,
    ERROR,
    AGENT_CANCELLED,
    RECOVERY_TIMEOUT,
    RECOVERY_FAILED,
    UNKNOWN;

    public static RunStopReason parseNullable(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
