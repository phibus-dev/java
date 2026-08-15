package dev.phibus.s3.web;

import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseReplicationObservabilityService;
import dev.phibus.s3.clickhouse.ClickHouseReplicationSnapshotStore;
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
public class ClickHouseReplicationController {
    private final ClickHouseReplicationObservabilityService observability;
    private final ClickHouseReplicationSnapshotStore history;
    private final ClickHouseProfileService profiles;

    public ClickHouseReplicationController(ClickHouseReplicationObservabilityService observability,
                                           ClickHouseReplicationSnapshotStore history,
                                           ClickHouseProfileService profiles) {
        this.observability = observability;
        this.history = history;
        this.profiles = profiles;
    }

    @GetMapping("/clickhouse/replication")
    public String page(Model model) {
        model.addAttribute("profiles", profiles.list());
        return "clickhouse-replication";
    }

    @GetMapping("/api/clickhouse/replication")
    @ResponseBody
    public ClickHouseReplicationObservabilityService.Snapshot snapshot(@RequestParam UUID profileId) {
        return observability.snapshot(profileId);
    }

    @GetMapping("/api/clickhouse/replication/history")
    @ResponseBody
    public List<ClickHouseReplicationSnapshotStore.HistoryRow> history(
            @RequestParam UUID profileId,
            @RequestParam(required = false) String endpoint,
            @RequestParam(defaultValue = "300") int limit) {
        return history.history(profileId, endpoint, limit);
    }
}
