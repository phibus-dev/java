package dev.phibus.s3.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        ClickHouseReplicatedScenarioController.class,
        ClickHouseFailoverController.class
})
public class ClickHouseWorkflowExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return response(HttpStatus.BAD_REQUEST, error);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> unavailable(RuntimeException error) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, error);
    }

    private static ResponseEntity<Map<String, String>> response(HttpStatus status, Throwable error) {
        return ResponseEntity.status(status).body(Map.of(
                "error", status.getReasonPhrase(),
                "message", rootMessage(error)));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
