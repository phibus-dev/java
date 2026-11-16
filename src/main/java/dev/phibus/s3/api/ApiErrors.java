package dev.phibus.s3.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

public final class ApiErrors {
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private ApiErrors() { }

    public static Envelope create(HttpServletRequest request, HttpStatus status, String code, String detail) {
        String requestId = requestId(request.getHeader(REQUEST_ID_HEADER));
        String normalizedCode = code == null || code.isBlank() ? "INTERNAL_ERROR" : code;
        String type = "urn:evo-snt:error:" + normalizedCode.toLowerCase(Locale.ROOT).replace('_', '-');
        ApiErrorResponse body = new ApiErrorResponse(type, status.getReasonPhrase(), status.value(),
                normalizedCode, detail, request.getRequestURI(), requestId, Instant.now());
        return new Envelope(body, requestId);
    }

    public static void write(ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, String code, String detail) throws IOException {
        Envelope envelope = create(request, status, code, detail);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(REQUEST_ID_HEADER, envelope.requestId());
        objectMapper.writeValue(response.getOutputStream(), envelope.body());
    }

    private static String requestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate.trim()).matches()) return candidate.trim();
        return UUID.randomUUID().toString();
    }

    public record Envelope(ApiErrorResponse body, String requestId) { }
}
