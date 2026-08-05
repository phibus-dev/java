package dev.phibus.s3.web;

import dev.phibus.s3.history.AdvancedHistoryStore;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class HistoryController {
    private final AdvancedHistoryStore historyStore;
    private final TestRunService testRunService;

    public HistoryController(AdvancedHistoryStore historyStore, TestRunService testRunService) {
        this.historyStore = historyStore;
        this.testRunService = testRunService;
    }

    @GetMapping("/history")
    public String history(@RequestParam(required = false) String status,
                          @RequestParam(required = false) String operation,
                          @RequestParam(required = false) String endpoint,
                          @RequestParam(required = false) String bucket,
                          @RequestParam(required = false, name = "q") String query,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "50") int size,
                          Model model) {
        AdvancedHistoryStore.Filter filter = new AdvancedHistoryStore.Filter(status, operation, endpoint, bucket,
                query, from, to, page, size);
        model.addAttribute("result", historyStore.search(filter));
        model.addAttribute("filter", filter);
        return "history";
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
                                          @RequestParam(defaultValue = "50") int size) {
        return historyStore.search(new AdvancedHistoryStore.Filter(status, operation, endpoint, bucket, query,
                from, to, page, size));
    }

    @GetMapping("/api/history/{id}")
    @ResponseBody
    public AdvancedHistoryStore.Detail get(@PathVariable UUID id) { return requireDetail(id); }

    @GetMapping("/api/history/compare")
    @ResponseBody
    public AdvancedHistoryStore.Comparison compare(@RequestParam UUID left, @RequestParam UUID right) {
        AdvancedHistoryStore.Comparison comparison = historyStore.compare(left, right);
        if (comparison == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History item not found");
        return comparison;
    }

    @PostMapping("/api/history/{id}/rerun")
    @ResponseBody
    public TestRun.Snapshot rerun(@PathVariable UUID id) {
        AdvancedHistoryStore.Detail detail = requireDetail(id);
        return testRunService.create(detail.repeatRequest()).snapshot();
    }

    private AdvancedHistoryStore.Detail requireDetail(UUID id) {
        AdvancedHistoryStore.Detail detail = historyStore.get(id);
        if (detail == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History item not found");
        return detail;
    }
}
