package dev.phibus.s3.web;

import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseReplicatedScenarioService;
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

    @GetMapping("/api/clickhouse/replicated-tests")
    @ResponseBody
    public List<ClickHouseReplicatedScenarioService.Snapshot> list(
            @RequestParam(required = false) UUID profileId,
            @RequestParam(defaultValue = "100") int limit) {
        return scenarios.list(profileId, limit);
    }
}
