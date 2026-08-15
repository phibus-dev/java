package dev.phibus.s3.clickhouse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseReplicationSnapshotStore {
    private final JdbcTemplate jdbc;

    public ClickHouseReplicationSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(ClickHouseReplicationObservabilityService.Snapshot snapshot) {
        for (ClickHouseReplicationObservabilityService.NodeSnapshot node : snapshot.nodes()) {
            long activeParts = node.parts().stream().mapToLong(ClickHouseReplicationObservabilityService.PartSummary::activeParts).sum();
            long rows = node.parts().stream().mapToLong(ClickHouseReplicationObservabilityService.PartSummary::rows).sum();
            long bytes = node.parts().stream().mapToLong(ClickHouseReplicationObservabilityService.PartSummary::bytesOnDisk).sum();
            long failedMutations = node.mutations().stream()
                    .filter(m -> m.latestFailReason() != null && !m.latestFailReason().isBlank()).count();
            ClickHouseReplicationObservabilityService.Health h = node.health();
            jdbc.update("""
                    INSERT INTO clickhouse_replication_snapshot(
                        id, profile_id, collected_at, endpoint, database_name, health_status, reachable,
                        readonly_replicas, expired_sessions, inactive_replicas, queue_size,
                        max_absolute_delay_seconds, max_log_lag, active_parts, rows_in_active_parts,
                        bytes_on_disk, active_merges, failed_mutations, error)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), snapshot.profileId(), ClickHouseJdbcTime.timestamptz(snapshot.collectedAt()),
                    node.endpoint(), snapshot.database(),
                    h.status(), node.reachable(), h.readonlyReplicas(), h.expiredSessions(), h.inactiveReplicas(),
                    h.queueSize(), h.maxAbsoluteDelaySeconds(), h.maxLogLag(), activeParts, rows, bytes,
                    node.merges().size(), failedMutations, node.error());
        }
    }

    public List<HistoryRow> history(UUID profileId, String endpoint, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 2000));
        if (endpoint == null || endpoint.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM clickhouse_replication_snapshot
                     WHERE profile_id = ?
                     ORDER BY collected_at DESC, endpoint
                     LIMIT ?
                    """, this::map, profileId, safeLimit);
        }
        return jdbc.query("""
                SELECT * FROM clickhouse_replication_snapshot
                 WHERE profile_id = ? AND endpoint = ?
                 ORDER BY collected_at DESC
                 LIMIT ?
                """, this::map, profileId, endpoint.trim(), safeLimit);
    }

    private HistoryRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        OffsetDateTime collected = rs.getObject("collected_at", OffsetDateTime.class);
        return new HistoryRow(rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class),
                collected == null ? null : collected.toInstant(), rs.getString("endpoint"), rs.getString("database_name"),
                rs.getString("health_status"), rs.getBoolean("reachable"), rs.getLong("readonly_replicas"),
                rs.getLong("expired_sessions"), rs.getLong("inactive_replicas"), rs.getLong("queue_size"),
                rs.getLong("max_absolute_delay_seconds"), rs.getLong("max_log_lag"), rs.getLong("active_parts"),
                rs.getLong("rows_in_active_parts"), rs.getLong("bytes_on_disk"), rs.getLong("active_merges"),
                rs.getLong("failed_mutations"), rs.getString("error"));
    }

    public record HistoryRow(UUID id, UUID profileId, Instant collectedAt, String endpoint, String database,
                             String healthStatus, boolean reachable, long readonlyReplicas, long expiredSessions,
                             long inactiveReplicas, long queueSize, long maxAbsoluteDelaySeconds, long maxLogLag,
                             long activeParts, long rowsInActiveParts, long bytesOnDisk, long activeMerges,
                             long failedMutations, String error) { }
}
