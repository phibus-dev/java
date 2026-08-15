package dev.phibus.s3.clickhouse;

import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseReplicationMetrics {
    private final MultiGauge health;
    private final MultiGauge queue;
    private final MultiGauge delay;
    private final MultiGauge logLag;
    private final MultiGauge readonly;
    private final MultiGauge inactive;

    public ClickHouseReplicationMetrics(MeterRegistry registry) {
        health = MultiGauge.builder("s3perf_clickhouse_replication_health").description("ClickHouse replication health: 0 OK, 1 WARNING, 2 CRITICAL").register(registry);
        queue = MultiGauge.builder("s3perf_clickhouse_replication_queue_size").description("ClickHouse replication queue size").register(registry);
        delay = MultiGauge.builder("s3perf_clickhouse_replication_absolute_delay_seconds").description("Maximum ClickHouse replication absolute delay").register(registry);
        logLag = MultiGauge.builder("s3perf_clickhouse_replication_log_lag").description("Maximum ClickHouse replication log lag").register(registry);
        readonly = MultiGauge.builder("s3perf_clickhouse_replication_readonly_replicas").description("Readonly replicas on ClickHouse node").register(registry);
        inactive = MultiGauge.builder("s3perf_clickhouse_replication_inactive_replicas").description("Inactive replicas visible from ClickHouse node").register(registry);
    }

    public void update(ClickHouseReplicationObservabilityService.Snapshot snapshot) {
        List<MultiGauge.Row<?>> healthRows = new ArrayList<>();
        List<MultiGauge.Row<?>> queueRows = new ArrayList<>();
        List<MultiGauge.Row<?>> delayRows = new ArrayList<>();
        List<MultiGauge.Row<?>> lagRows = new ArrayList<>();
        List<MultiGauge.Row<?>> readonlyRows = new ArrayList<>();
        List<MultiGauge.Row<?>> inactiveRows = new ArrayList<>();
        for (ClickHouseReplicationObservabilityService.NodeSnapshot node : snapshot.nodes()) {
            Tags tags = Tags.of("profile", snapshot.profileName(), "database", snapshot.database(), "endpoint", node.endpoint());
            ClickHouseReplicationObservabilityService.Health h = node.health();
            healthRows.add(MultiGauge.Row.of(tags, healthCode(h.status())));
            queueRows.add(MultiGauge.Row.of(tags, h.queueSize()));
            delayRows.add(MultiGauge.Row.of(tags, h.maxAbsoluteDelaySeconds()));
            lagRows.add(MultiGauge.Row.of(tags, h.maxLogLag()));
            readonlyRows.add(MultiGauge.Row.of(tags, h.readonlyReplicas()));
            inactiveRows.add(MultiGauge.Row.of(tags, h.inactiveReplicas()));
        }
        health.register(healthRows, true);
        queue.register(queueRows, true);
        delay.register(delayRows, true);
        logLag.register(lagRows, true);
        readonly.register(readonlyRows, true);
        inactive.register(inactiveRows, true);
    }

    static int healthCode(String status) {
        return "CRITICAL".equals(status) ? 2 : "WARNING".equals(status) ? 1 : 0;
    }
}
