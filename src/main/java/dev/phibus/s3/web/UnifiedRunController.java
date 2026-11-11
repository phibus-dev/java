package dev.phibus.s3.web;

import dev.phibus.s3.lifecycle.RunLifecycleView;
import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.lifecycle.UnifiedRunService;
import dev.phibus.s3.test.TestType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class UnifiedRunController {
    private final UnifiedRunService service;

    public UnifiedRunController(UnifiedRunService service) {
        this.service = service;
    }

    @GetMapping
    public List<RunLifecycleView> list(@RequestParam(required = false) String scope,
                                       @RequestParam(required = false) String testType,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false, name = "q") String query,
                                       @RequestParam(defaultValue = "200") int limit) {
        return service.list(parseScope(scope), parseTestType(testType), parseStatus(status), query, limit);
    }

    @GetMapping("/{id}")
    public RunLifecycleView get(@PathVariable UUID id) {
        return service.get(id);
    }

    private static RunScope parseScope(String value) {
        return parseEnum(value, RunScope.class, "scope");
    }

    private static TestType parseTestType(String value) {
        return parseEnum(value, TestType.class, "testType");
    }

    private static RunStatus parseStatus(String value) {
        return value == null || value.isBlank() ? null : RunStatus.parse(value);
    }

    private static <T extends Enum<T>> T parseEnum(String value, Class<T> type, String parameter) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unsupported " + parameter + ": " + value, error);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse badRequest(IllegalArgumentException error) {
        return new ErrorResponse(error.getMessage());
    }

    @ExceptionHandler(UnifiedRunService.RunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse notFound(UnifiedRunService.RunNotFoundException error) {
        return new ErrorResponse(error.getMessage());
    }

    public record ErrorResponse(String error) { }
}
