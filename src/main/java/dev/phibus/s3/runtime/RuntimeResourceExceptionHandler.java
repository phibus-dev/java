package dev.phibus.s3.runtime;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RuntimeResourceExceptionHandler {
    @ExceptionHandler(RuntimeResourceManager.RunCapacityExceededException.class)
    ResponseEntity<Map<String, Object>> capacityExceeded(
            RuntimeResourceManager.RunCapacityExceededException error) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(Map.of("timestamp", Instant.now().toString(), "status", 429,
                        "error", "Runtime capacity exceeded", "message", error.getMessage()));
    }
}
