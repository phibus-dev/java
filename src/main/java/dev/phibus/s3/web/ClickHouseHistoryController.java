package dev.phibus.s3.web;

import dev.phibus.s3.clickhouse.ClickHouseHistoryStore;
import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseHistoryController {
    private static final String DEVELOPMENT_VERSION = "2.2.3-rc1";
    private final ClickHouseHistoryStore history;
    private final ClickHouseProfileService profiles;

    public ClickHouseHistoryController(ClickHouseHistoryStore history, ClickHouseProfileService profiles) {
        this.history = history;
        this.profiles = profiles;
    }

    @GetMapping("/clickhouse")
    public String tests(Model model) {
        model.addAttribute("profiles", profiles.list());
        model.addAttribute("history", history.list(100));
        model.addAttribute("applicationVersion", applicationVersion());
        return "clickhouse-tests";
    }

    @GetMapping("/clickhouse/history/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        model.addAttribute("run", history.get(id));
        model.addAttribute("applicationVersion", applicationVersion());
        return "clickhouse-history-detail";
    }

    @GetMapping("/clickhouse/compare")
    public String comparePage(@RequestParam UUID left, @RequestParam UUID right, Model model) {
        model.addAttribute("comparison", history.compare(left, right));
        model.addAttribute("applicationVersion", applicationVersion());
        return "clickhouse-compare";
    }

    @GetMapping("/api/clickhouse/history")
    @ResponseBody
    public List<ClickHouseHistoryStore.HistoryRow> history(@RequestParam(defaultValue = "100") int limit) {
        return history.list(limit);
    }

    @GetMapping("/api/clickhouse/history/{id}")
    @ResponseBody
    public ClickHouseHistoryStore.HistoryRow historyEntry(@PathVariable UUID id) {
        return history.get(id);
    }

    @GetMapping("/api/clickhouse/history/compare")
    @ResponseBody
    public ClickHouseHistoryStore.Comparison compare(@RequestParam UUID left, @RequestParam UUID right) {
        return history.compare(left, right);
    }

    @GetMapping("/api/clickhouse/history/trends")
    @ResponseBody
    public List<ClickHouseHistoryStore.TrendPoint> trends(@RequestParam(required = false) String operation,
                                                          @RequestParam(required = false) String table,
                                                          @RequestParam(defaultValue = "50") int limit) {
        return history.trends(operation, table, limit);
    }

    private static String applicationVersion() {
        String version = ClickHouseHistoryController.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? DEVELOPMENT_VERSION : version;
    }
}
