package dev.phibus.s3.lifecycle;

import java.util.Locale;

public enum RunStatus {
    ASSIGNED,
    QUEUED,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED;

    public static RunStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Run status is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unsupported run status: " + value, error);
        }
    }

    public boolean terminal() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS
                || this == FAILED || this == CANCELLED;
    }
}
