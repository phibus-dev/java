package dev.phibus.s3.api;

import dev.phibus.s3.lifecycle.UnifiedRunRerunService;
import dev.phibus.s3.lifecycle.UnifiedRunService;
import dev.phibus.s3.runtime.RuntimeResourceManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = VersionedRunApiController.class)
public class VersionedRunApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(VersionedRunApiExceptionHandler.class);

    @ExceptionHandler(UnifiedRunService.RunNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> runNotFound(UnifiedRunService.RunNotFoundException error,
                                                         HttpServletRequest request) {
        return response(request, HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", error.getMessage());
    }

    @ExceptionHandler(UnifiedRunRerunService.RunHistoryNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> historyNotFound(
            UnifiedRunRerunService.RunHistoryNotFoundException error, HttpServletRequest request) {
        return response(request, HttpStatus.NOT_FOUND, "RUN_HISTORY_NOT_FOUND", error.getMessage());
    }

    @ExceptionHandler(RuntimeResourceManager.RunCapacityExceededException.class)
    public ResponseEntity<ApiErrorResponse> capacityExceeded(
            RuntimeResourceManager.RunCapacityExceededException error, HttpServletRequest request) {
        ApiErrors.Envelope envelope = ApiErrors.create(request, HttpStatus.TOO_MANY_REQUESTS,
                "CAPACITY_EXCEEDED", error.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(ApiErrors.REQUEST_ID_HEADER, envelope.requestId())
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(envelope.body());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> conflict(IllegalStateException error, HttpServletRequest request) {
        return response(request, HttpStatus.CONFLICT, "RUN_STATE_CONFLICT", safeMessage(error));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiErrorResponse> invalidRequest(Exception error, HttpServletRequest request) {
        String message = error instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value for " + mismatch.getName() : safeMessage(error);
        return response(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> internal(RuntimeException error, HttpServletRequest request) {
        LOG.error("Versioned run API request failed: {}", request.getRequestURI(), error);
        return response(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
    }

    private static ResponseEntity<ApiErrorResponse> response(HttpServletRequest request, HttpStatus status,
                                                              String code, String detail) {
        ApiErrors.Envelope envelope = ApiErrors.create(request, status, code, detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(ApiErrors.REQUEST_ID_HEADER, envelope.requestId())
                .body(envelope.body());
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
