package dev.phibus.s3.api;

import dev.phibus.s3.lifecycle.RunLifecycleView;
import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import dev.phibus.s3.lifecycle.UnifiedRunRerunService;
import dev.phibus.s3.lifecycle.UnifiedRunService;
import dev.phibus.s3.test.TestType;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class VersionedRunApiController {
    private static final int MAX_RUNTIME_LIMIT = 500;
    private static final int MAX_HISTORY_PAGE_SIZE = 200;

    private final UnifiedRunService runs;
    private final UnifiedRunHistoryStore history;
    private final UnifiedRunRerunService rerunService;

    public VersionedRunApiController(UnifiedRunService runs, UnifiedRunHistoryStore history,
                                     UnifiedRunRerunService rerunService) {
        this.runs = runs;
        this.history = history;
        this.rerunService = rerunService;
    }

    @GetMapping("/runs")
    public List<RunLifecycleView> runs(@RequestParam(required = false) String scope,
                                       @RequestParam(required = false) String testType,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false, name = "q") String query,
                                       @RequestParam(defaultValue = "200") int limit) {
        requireRange("limit", limit, 1, MAX_RUNTIME_LIMIT);
        return runs.list(parseEnum(scope, RunScope.class, "scope"),
                parseEnum(testType, TestType.class, "testType"), parseStatus(status), query, limit);
    }

    @GetMapping("/runs/{id}")
    public RunLifecycleView run(@PathVariable UUID id) {
        return runs.get(id);
    }

    @GetMapping("/run-history")
    public UnifiedRunHistoryStore.Page history(@RequestParam(required = false) String scope,
                                                @RequestParam(required = false) String testType,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false, name = "q") String query,
                                                @RequestParam(required = false) Instant from,
                                                @RequestParam(required = false) Instant to,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        requireRange("page", page, 0, Integer.MAX_VALUE);
        requireRange("size", size, 1, MAX_HISTORY_PAGE_SIZE);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }
        return history.search(new UnifiedRunHistoryStore.Filter(parseEnum(scope, RunScope.class, "scope"),
                parseEnum(testType, TestType.class, "testType"), parseStatus(status), query,
                from, to, page, size));
    }

    @GetMapping("/run-history/{id}")
    public UnifiedRunHistoryStore.RunHistoryRow historyEntry(@PathVariable UUID id) {
        UnifiedRunHistoryStore.RunHistoryRow result = history.get(id);
        if (result == null) throw new UnifiedRunRerunService.RunHistoryNotFoundException(id);
        return result;
    }

    @PostMapping("/run-history/{id}/rerun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UnifiedRunRerunService.RerunResult rerun(@PathVariable UUID id, Principal principal) {
        return rerunService.rerun(id, principal == null ? null : principal.getName());
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

    private static void requireRange(String parameter, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(parameter + " must be between " + minimum + " and " + maximum);
        }
    }
}
