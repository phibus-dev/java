package dev.phibus.s3.web;

import dev.phibus.s3.clickhouse.ClickHouseHaDashboardService;
import dev.phibus.s3.clickhouse.ClickHouseKeeperHealthService;
import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseHaController {
    private final ClickHouseHaDashboardService dashboard;
    private final ClickHouseKeeperHealthService keeper;
    private final ClickHouseProfileService profiles;

    public ClickHouseHaController(ClickHouseHaDashboardService dashboard,
                                  ClickHouseKeeperHealthService keeper,
                                  ClickHouseProfileService profiles) {
        this.dashboard = dashboard;
        this.keeper = keeper;
        this.profiles = profiles;
    }

    @GetMapping("/clickhouse/ha")
    public String page(Model model) {
        model.addAttribute("profiles", profiles.list());
        model.addAttribute("presets", dashboard.presets());
        return "clickhouse-ha";
    }

    @GetMapping("/api/clickhouse/ha")
    @ResponseBody
    public ClickHouseHaDashboardService.Summary summary(@RequestParam UUID profileId) {
        return dashboard.summary(profileId);
    }

    @GetMapping("/api/clickhouse/keeper")
    @ResponseBody
    public ClickHouseKeeperHealthService.Snapshot keeper(@RequestParam UUID profileId) {
        return keeper.snapshot(profileId);
    }

    @GetMapping("/api/clickhouse/ha/presets")
    @ResponseBody
    public List<ClickHouseHaDashboardService.Preset> presets() {
        return dashboard.presets();
    }
}
