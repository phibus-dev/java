package dev.phibus.s3.web;

import dev.phibus.s3.history.AdvancedHistoryStore;
import dev.phibus.s3.history.HistoryTrendService;
import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import dev.phibus.s3.lifecycle.UnifiedRunRerunService;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import dev.phibus.s3.test.TestType;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

@Controller
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class HistoryController {
    private static final int HISTORY_PAGE_SIZE = 20;
    private final AdvancedHistoryStore historyStore;
    private final TestRunService testRunService;
    private final HistoryTrendService trendService;
    private final UnifiedRunHistoryStore unifiedHistory;
    private final UnifiedRunRerunService rerunService;

    public HistoryController(AdvancedHistoryStore historyStore, TestRunService testRunService,
                             HistoryTrendService trendService, UnifiedRunHistoryStore unifiedHistory,
                             UnifiedRunRerunService rerunService) {
        this.historyStore = historyStore;
        this.testRunService = testRunService;
        this.trendService = trendService;
        this.unifiedHistory = unifiedHistory;
        this.rerunService = rerunService;
    }

    @GetMapping("/history")
    public String history(@RequestParam(required = false) String scope,
                          @RequestParam(required = false) String testType,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false, name = "q") String query,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        UnifiedRunHistoryStore.Filter filter = new UnifiedRunHistoryStore.Filter(parseScope(scope),
                parseTestType(testType), parseStatus(status), query, parsePageInstant(from, "from"),
                parsePageInstant(to, "to"), page, HISTORY_PAGE_SIZE);
        model.addAttribute("result", unifiedHistory.search(filter));
        model.addAttribute("filter", filter);
        model.addAttribute("fromValue", pageInstantValue(filter.from()));
        model.addAttribute("toValue", pageInstantValue(filter.to()));
        return "history";
    }

    @GetMapping("/run-history/{id}")
    public String unifiedDetail(@PathVariable UUID id, Model model) {
        model.addAttribute("run", requireUnifiedDetail(id));
        return "run-history-detail";
    }

    @GetMapping("/history/compare")
    public String comparePage(@RequestParam UUID left, @RequestParam UUID right, Model model) {
        AdvancedHistoryStore.Comparison comparison = requireComparison(left, right);
        model.addAttribute("comparison", comparison);
        return "history-compare";
    }

    @GetMapping("/history/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        AdvancedHistoryStore.Detail detail = requireDetail(id);
        model.addAttribute("detail", detail);
        return "history-detail";
    }

    @GetMapping("/api/history")
    @ResponseBody
    public AdvancedHistoryStore.Page list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String operation,
                                          @RequestParam(required = false) String endpoint,
                                          @RequestParam(required = false) String bucket,
                                          @RequestParam(required = false, name = "q") String query,
                                          @RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int size) {
        return historyStore.search(new AdvancedHistoryStore.Filter(status, operation, endpoint, bucket, query,
                from, to, page, size));
    }

    @GetMapping("/api/history/{id}")
    @ResponseBody
    public AdvancedHistoryStore.Detail get(@PathVariable UUID id) { return requireDetail(id); }

    @GetMapping("/api/run-history")
    @ResponseBody
    public UnifiedRunHistoryStore.Page unifiedHistory(@RequestParam(required = false) String scope,
                                                       @RequestParam(required = false) String testType,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false, name = "q") String query,
                                                       @RequestParam(required = false) Instant from,
                                                       @RequestParam(required = false) Instant to,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return unifiedHistory.search(new UnifiedRunHistoryStore.Filter(parseScope(scope), parseTestType(testType),
                parseStatus(status), query, from, to, page, size));
    }

    @GetMapping("/api/run-history/{id}")
    @ResponseBody
    public UnifiedRunHistoryStore.RunHistoryRow unifiedHistoryEntry(@PathVariable UUID id) {
        return requireUnifiedDetail(id);
    }

    @GetMapping("/api/history/compare")
    @ResponseBody
    public AdvancedHistoryStore.Comparison compare(@RequestParam UUID left, @RequestParam UUID right) {
        return requireComparison(left, right);
    }

    @GetMapping("/api/history/trends")
    @ResponseBody
    public HistoryTrendService.TrendReport trends(@RequestParam List<UUID> ids) {
        try {
            return trendService.build(ids);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/api/history/{id}/rerun")
    @ResponseBody
    public TestRun.Snapshot rerun(@PathVariable UUID id) {
        AdvancedHistoryStore.Detail detail = requireDetail(id);
        return testRunService.create(detail.repeatRequest()).snapshot();
    }

    @PostMapping("/api/run-history/{id}/rerun")
    @ResponseBody
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UnifiedRunRerunService.RerunResult rerunUnified(@PathVariable UUID id, Principal principal) {
        try {
            return rerunService.rerun(id, principal == null ? null : principal.getName());
        } catch (UnifiedRunRerunService.RunHistoryNotFoundException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    private AdvancedHistoryStore.Detail requireDetail(UUID id) {
        AdvancedHistoryStore.Detail detail = historyStore.get(id);
        if (detail == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History item not found");
        return detail;
    }

    private AdvancedHistoryStore.Comparison requireComparison(UUID left, UUID right) {
        AdvancedHistoryStore.Comparison comparison = historyStore.compare(left, right);
        if (comparison == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History item not found");
        return comparison;
    }

    private UnifiedRunHistoryStore.RunHistoryRow requireUnifiedDetail(UUID id) {
        UnifiedRunHistoryStore.RunHistoryRow run = unifiedHistory.get(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run history entry not found");
        return run;
    }

    private static RunScope parseScope(String value) {
        return parseEnum(value, RunScope.class, "scope");
    }

    private static TestType parseTestType(String value) {
        return parseEnum(value, TestType.class, "testType");
    }

    private static RunStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return RunStatus.parse(value);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    private static Instant parsePageInstant(String value, String parameter) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported " + parameter + " timestamp: " + value, error);
            }
        }
    }

    private static String pageInstantValue(Instant value) {
        return value == null ? "" : DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                .withZone(ZoneOffset.UTC).format(value);
    }

    private static <T extends Enum<T>> T parseEnum(String value, Class<T> type, String parameter) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported " + parameter + ": " + value, error);
        }
    }
}
