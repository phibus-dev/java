package dev.phibus.s3.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {AgentController.class, DistributedTestController.class})
public class AgentProtocolExceptionHandler {
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> unauthorized(SecurityException error) {
        return response(HttpStatus.UNAUTHORIZED, error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        HttpStatus status = error.getMessage() != null && error.getMessage().startsWith("Agent not found")
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return response(status, error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
        return response(HttpStatus.CONFLICT, error);
    }

    private static ResponseEntity<Map<String, String>> response(HttpStatus status, Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return ResponseEntity.status(status).body(Map.of(
                "error", status.getReasonPhrase(),
                "message", message));
    }
}
