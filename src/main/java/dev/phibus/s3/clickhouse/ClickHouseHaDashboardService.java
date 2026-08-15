package dev.phibus.s3.clickhouse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseHaDashboardService {
    private final ClickHouseReplicationObservabilityService replication;
    private final ClickHouseKeeperHealthService keeper;

    public ClickHouseHaDashboardService(ClickHouseReplicationObservabilityService replication,
                                        ClickHouseKeeperHealthService keeper) {
        this.replication = replication;
        this.keeper = keeper;
    }

    public Summary summary(UUID profileId) {
        ClickHouseReplicationObservabilityService.Snapshot rep = replication.snapshot(profileId);
        ClickHouseKeeperHealthService.Snapshot kep = keeper.snapshot(profileId);
        long nodes = rep.nodes().size();
        long reachable = rep.nodes().stream().filter(ClickHouseReplicationObservabilityService.NodeSnapshot::reachable).count();
        long queue = rep.nodes().stream().mapToLong(n -> n.health().queueSize()).sum();
        long maxDelay = rep.nodes().stream().mapToLong(n -> n.health().maxAbsoluteDelaySeconds()).max().orElse(0);
        long maxLag = rep.nodes().stream().mapToLong(n -> n.health().maxLogLag()).max().orElse(0);
        long readonly = rep.nodes().stream().mapToLong(n -> n.health().readonlyReplicas()).sum();
        long inactive = rep.nodes().stream().mapToLong(n -> n.health().inactiveReplicas()).sum();
        String replicationStatus = rep.nodes().stream().anyMatch(n -> !n.reachable() || "CRITICAL".equals(n.health().status())) ? "FAIL"
                : rep.nodes().stream().anyMatch(n -> "WARNING".equals(n.health().status())) ? "WARNING" : "PASS";
        String keeperStatus = "OK".equals(kep.status()) ? "PASS" : "CRITICAL".equals(kep.status()) ? "FAIL" : "WARNING";
        String overall = "FAIL".equals(replicationStatus) || "FAIL".equals(keeperStatus) ? "FAIL"
                : "WARNING".equals(replicationStatus) || "WARNING".equals(keeperStatus) ? "WARNING" : "PASS";
        return new Summary(profileId, rep.profileName(), rep.database(), Instant.now(), overall, replicationStatus,
                keeperStatus, reachable, nodes, queue, maxDelay, maxLag, readonly, inactive, kep);
    }

    public List<Preset> presets() {
        return List.of(
                new Preset("REPLICATED_INSERT_PERFORMANCE", "Replicated Insert Performance", "REPLICATED_INSERT", 1_000_000, 10_000, 128, 120),
                new Preset("REPLICATION_CATCHUP", "Replication Catch-up", "REPLICATION_CATCHUP", 1_000_000, 10_000, 128, 180),
                new Preset("REPLICA_CONSISTENCY", "Replica Consistency", "REPLICA_CONSISTENCY", 1, 1, 1, 60),
                new Preset("REPLICA_FAILURE_RECOVERY", "Replica Failure & Recovery", "FAILOVER", 5_000_000, 5_000, 128, 300),
                new Preset("REPLICATION_BACKLOG", "Replication Backlog", "REPLICATION_CATCHUP", 10_000_000, 25_000, 256, 300),
                new Preset("KEEPER_HEALTH", "Keeper Health", "KEEPER_HEALTH", 0, 0, 0, 30));
    }

    public record Summary(UUID profileId, String profileName, String database, Instant collectedAt, String overall,
                          String replication, String keeper, long reachableNodes, long totalNodes, long queueSize,
                          long maxDelaySeconds, long maxLogLag, long readonlyReplicas, long inactiveReplicas,
                          ClickHouseKeeperHealthService.Snapshot keeperSnapshot) { }
    public record Preset(String id, String name, String scenario, long rows, int batchSize, int payloadBytes,
                         long timeoutSeconds) { }
}
