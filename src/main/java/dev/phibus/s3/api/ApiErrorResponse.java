package dev.phibus.s3.api;

import java.time.Instant;

public record ApiErrorResponse(String type, String title, int status, String code, String detail,
                               String instance, String requestId, Instant timestamp) { }
