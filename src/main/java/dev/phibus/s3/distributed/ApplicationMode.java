package dev.phibus.s3.distributed;

public enum ApplicationMode {
    COORDINATOR,
    AGENT;

    public static ApplicationMode from(String value) {
        if (value == null || value.isBlank()) return COORDINATOR;
        return ApplicationMode.valueOf(value.trim().toUpperCase());
    }
}
