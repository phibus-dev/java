package dev.phibus.s3.web;

import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseReplicaFailoverService;
import dev.phibus.s3.clickhouse.ClickHouseReplicatedTableAdminService;
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
public class ClickHouseFailoverController {
    private final ClickHouseProfileService profiles;
    private final ClickHouseReplicatedTableAdminService tables;
    private final ClickHouseReplicaFailoverService failover;

    public ClickHouseFailoverController(ClickHouseProfileService profiles,
                                        ClickHouseReplicatedTableAdminService tables,
                                        ClickHouseReplicaFailoverService failover) {
        this.profiles = profiles;
        this.tables = tables;
        this.failover = failover;
    }

    @GetMapping("/clickhouse/failover-tests")
    public String page(Model model) {
        model.addAttribute("profiles", profiles.list());
        model.addAttribute("history", failover.list(null, 100));
        return "clickhouse-failover-tests";
    }

    @PostMapping("/api/clickhouse/replicated-tables")
    @ResponseBody
    public ClickHouseReplicatedTableAdminService.ProvisionResult provision(
            @RequestBody ClickHouseReplicatedTableAdminService.ProvisionRequest request) {
        return tables.provision(request);
    }

    @PostMapping("/api/clickhouse/failover-tests")
    @ResponseBody
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClickHouseReplicaFailoverService.Snapshot create(@RequestBody ClickHouseReplicaFailoverService.Request request) {
        return failover.create(request);
    }

    @PostMapping("/api/clickhouse/failover-tests/{id}/fault-applied")
    @ResponseBody
    public ClickHouseReplicaFailoverService.Snapshot faultApplied(@PathVariable UUID id) {
        return failover.confirmFault(id);
    }

    @PostMapping("/api/clickhouse/failover-tests/{id}/recovery-started")
    @ResponseBody
    public ClickHouseReplicaFailoverService.Snapshot recoveryStarted(@PathVariable UUID id) {
        return failover.startRecovery(id);
    }

    @GetMapping("/api/clickhouse/failover-tests/{id}")
    @ResponseBody
    public ClickHouseReplicaFailoverService.Snapshot get(@PathVariable UUID id) {
        return failover.get(id);
    }

    @GetMapping("/api/clickhouse/failover-tests")
    @ResponseBody
    public List<ClickHouseReplicaFailoverService.Snapshot> list(
            @RequestParam(required = false) UUID profileId,
            @RequestParam(defaultValue = "100") int limit) {
        return failover.list(profileId, limit);
    }
}
