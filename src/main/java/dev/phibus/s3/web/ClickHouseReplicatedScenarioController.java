package dev.phibus.s3.web;

import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseReplicatedScenarioService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseReplicatedScenarioController {
    private static final ZoneId UI_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter UI_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(UI_ZONE);

    private final ClickHouseReplicatedScenarioService scenarios;
    private final ClickHouseProfileService profiles;

    public ClickHouseReplicatedScenarioController(ClickHouseReplicatedScenarioService scenarios,
                                                  ClickHouseProfileService profiles) {
        this.scenarios = scenarios;
        this.profiles = profiles;
    }

    @GetMapping("/clickhouse/replicated-tests")
    public String page(Model model) {
        model.addAttribute("profiles", profiles.list());
        model.addAttribute("history", scenarios.list(null, 100));
        return "clickhouse-replicated-tests";
    }

    @GetMapping("/clickhouse/replicated-tests/history/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        ClickHouseReplicatedScenarioService.Snapshot run = scenarios.get(id);
        model.addAttribute("run", run);
        model.addAttribute("createdAtFormatted", formatUiTime(run.createdAt()));
        model.addAttribute("startedAtFormatted", formatUiTime(run.startedAt()));
        model.addAttribute("finishedAtFormatted", formatUiTime(run.finishedAt()));
        model.addAttribute("actualDurationMillis", actualDurationMillis(run));
        model.addAttribute("consistencyDetails", scenarios.consistencyDetails(id));
        return "clickhouse-replicated-history-detail";
    }

    @PostMapping("/api/clickhouse/replicated-tests")
    @ResponseBody
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClickHouseReplicatedScenarioService.Snapshot create(
            @RequestBody ClickHouseReplicatedScenarioService.Request request) {
        return scenarios.create(request);
    }

    @GetMapping("/api/clickhouse/replicated-tests/{id}")
    @ResponseBody
    public ClickHouseReplicatedScenarioService.Snapshot get(@PathVariable UUID id) {
        return scenarios.get(id);
    }

    @GetMapping("/api/clickhouse/replicated-tests/{id}/consistency")
    @ResponseBody
    public List<ClickHouseReplicatedScenarioService.ConsistencyDetail> consistency(@PathVariable UUID id) {
        scenarios.get(id);
        return scenarios.consistencyDetails(id);
    }

    @GetMapping("/api/clickhouse/replicated-tests")
    @ResponseBody
    public List<ClickHouseReplicatedScenarioService.Snapshot> list(
            @RequestParam(required = false) UUID profileId,
            @RequestParam(defaultValue = "100") int limit) {
        return scenarios.list(profileId, limit);
    }

    static long actualDurationMillis(ClickHouseReplicatedScenarioService.Snapshot run) {
        if (run.startedAt() == null || run.finishedAt() == null) return 0;
        return Math.max(0, Duration.between(run.startedAt(), run.finishedAt()).toMillis());
    }

    static String formatUiTime(Instant value) {
        return value == null ? "—" : UI_DATE_TIME.format(value);
    }
}
