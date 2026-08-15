package dev.phibus.s3.clickhouse;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseReplicatedScenarioService {
    private final ClickHouseProfileService profiles;
    private final ClickHouseReplicationObservabilityService observability;
    private final JdbcTemplate jdbc;
    private final Executor executor;
    private final Map<UUID, Run> active = new ConcurrentHashMap<>();

    public ClickHouseReplicatedScenarioService(ClickHouseProfileService profiles,
                                               ClickHouseReplicationObservabilityService observability,
                                               JdbcTemplate jdbc,
                                               @Qualifier("clickHouseWorkflowExecutor") Executor executor) {
        this.profiles = profiles;
        this.observability = observability;
        this.jdbc = jdbc;
        this.executor = executor;
    }

    public Snapshot create(Request request) {
        validate(request);
        ClickHouseProfileService.Profile profile = profiles.get(request.profileId());
        String source = request.sourceEndpoint() == null || request.sourceEndpoint().isBlank()
                ? profile.endpoints().getFirst() : request.sourceEndpoint().trim();
        if (!profile.endpoints().contains(source)) throw new IllegalArgumentException("Source endpoint is not part of profile");
        Run run = new Run(UUID.randomUUID(), request, source, Instant.now());
        persist(run.snapshot());
        active.put(run.id, run);
        try {
            executor.execute(() -> execute(run));
        } catch (RuntimeException e) {
            active.remove(run.id);
            run.status = "FAILED";
            run.finishedAt = Instant.now();
            run.message = "Cannot schedule replicated scenario: " + rootMessage(e);
            persist(run.snapshot());
            throw e;
        }
        return run.snapshot();
    }

    public Snapshot get(UUID id) {
        Run run = active.get(id);
        if (run != null) return run.snapshot();
        List<Snapshot> rows = jdbc.query("SELECT * FROM clickhouse_replicated_scenario_run WHERE id=?", (rs, n) -> map(rs), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Replicated scenario run not found: " + id);
        return rows.getFirst();
    }

    public List<Snapshot> list(UUID profileId, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        if (profileId == null) return jdbc.query("SELECT * FROM clickhouse_replicated_scenario_run ORDER BY created_at DESC LIMIT ?", (rs,n)->map(rs), safe);
        return jdbc.query("SELECT * FROM clickhouse_replicated_scenario_run WHERE profile_id=? ORDER BY created_at DESC LIMIT ?", (rs,n)->map(rs), profileId, safe);
    }

    private void execute(Run run) {
        run.status = "RUNNING";
        run.startedAt = Instant.now();
        persist(run.snapshot());
        try {
            switch (run.request.scenario().toUpperCase()) {
                case "REPLICATED_INSERT" -> replicatedInsert(run, true);
                case "REPLICATION_CATCHUP" -> replicatedInsert(run, true);
                case "REPLICA_CONSISTENCY" -> consistency(run);
                default -> throw new IllegalArgumentException("Unsupported scenario: " + run.request.scenario());
            }
            run.status = "COMPLETED";
            run.message = run.consistencyPassed == null || run.consistencyPassed ? "Scenario completed" : "Replica consistency check failed";
        } catch (Exception e) {
            run.status = "FAILED";
            run.message = rootMessage(e);
        } finally {
            run.finishedAt = Instant.now();
            persist(run.snapshot());
            active.remove(run.id);
        }
    }

    private void replicatedInsert(Run run, boolean waitCatchup) throws Exception {
        Request request = run.request;
        byte[] bytes = new byte[request.payloadBytes()];
        java.util.Arrays.fill(bytes, (byte) 'x');
        String payload = new String(bytes, StandardCharsets.US_ASCII);
        long startedNs = System.nanoTime();
        try (Connection c = profiles.open(request.profileId(), run.sourceEndpoint);
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + table(request.table()) + " (event_time, sequence, payload) VALUES (now64(3), ?, ?)")) {
            long sequence = System.currentTimeMillis() * 1000L;
            long written = 0;
            while (written < request.rows()) {
                int batch = (int) Math.min(request.batchSize(), request.rows() - written);
                for (int i = 0; i < batch; i++) {
                    ps.setLong(1, sequence + written + i);
                    ps.setString(2, payload);
                    ps.addBatch();
                }
                Instant batchStart = Instant.now();
                ps.executeBatch();
                run.insertLatencyMs = Duration.between(batchStart, Instant.now()).toMillis();
                written += batch;
                run.rowsWritten = written;
            }
        }
        double seconds = (System.nanoTime() - startedNs) / 1_000_000_000.0;
        run.insertRowsPerSecond = seconds <= 0 ? 0 : run.rowsWritten / seconds;
        if (waitCatchup) waitForCatchup(run);
        consistency(run);
    }

    private void waitForCatchup(Run run) throws InterruptedException {
        Instant started = Instant.now();
        long timeout = Math.max(1, run.request.catchupTimeoutSeconds());
        while (Duration.between(started, Instant.now()).getSeconds() <= timeout) {
            ClickHouseReplicationObservabilityService.Snapshot snapshot = observability.snapshot(run.request.profileId());
            long maxDelay = snapshot.nodes().stream().mapToLong(n -> n.health().maxAbsoluteDelaySeconds()).max().orElse(0);
            long maxLag = snapshot.nodes().stream().mapToLong(n -> n.health().maxLogLag()).max().orElse(0);
            long queue = snapshot.nodes().stream().mapToLong(n -> n.health().queueSize()).max().orElse(0);
            run.maxReplicationDelaySeconds = Math.max(run.maxReplicationDelaySeconds, maxDelay);
            run.maxLogLag = Math.max(run.maxLogLag, maxLag);
            run.maxReplicationQueue = Math.max(run.maxReplicationQueue, queue);
            boolean healthy = snapshot.nodes().stream().allMatch(n -> n.reachable() && n.health().readonlyReplicas() == 0
                    && n.health().expiredSessions() == 0 && n.health().inactiveReplicas() == 0);
            if (healthy && maxDelay == 0 && maxLag == 0 && queue == 0) {
                run.replicationCatchupMs = Duration.between(started, Instant.now()).toMillis();
                return;
            }
            Thread.sleep(Math.max(200, run.request.pollIntervalMs()));
        }
        run.replicationCatchupMs = Duration.between(started, Instant.now()).toMillis();
        throw new IllegalStateException("Replication did not catch up within " + timeout + " seconds");
    }

    private void consistency(Run run) throws Exception {
        ClickHouseProfileService.Profile profile = profiles.get(run.request.profileId());
        List<ReplicaDigest> digests = new ArrayList<>();
        for (String endpoint : profile.endpoints()) {
            try (Connection c = profiles.open(run.request.profileId(), endpoint);
                 PreparedStatement ps = c.prepareStatement("SELECT count(), sum(sequence), sum(length(payload)) FROM " + table(run.request.table()));
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) digests.add(new ReplicaDigest(endpoint, rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
        }
        run.replicaCount = digests.size();
        if (digests.isEmpty()) throw new IllegalStateException("No replicas available for consistency check");
        ReplicaDigest first = digests.getFirst();
        run.consistencyPassed = digests.stream().allMatch(d -> d.rows == first.rows
                && java.util.Objects.equals(d.sequenceSum, first.sequenceSum)
                && java.util.Objects.equals(d.payloadBytes, first.payloadBytes));
    }

    private void persist(Snapshot s) {
        jdbc.update("""
            INSERT INTO clickhouse_replicated_scenario_run(id,profile_id,scenario,table_name,source_endpoint,status,created_at,started_at,finished_at,
              rows_written,insert_rows_per_second,insert_latency_ms,replication_catchup_ms,max_replication_delay_seconds,max_replication_queue,max_log_lag,
              consistency_passed,replica_count,message)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status,started_at=EXCLUDED.started_at,finished_at=EXCLUDED.finished_at,
              rows_written=EXCLUDED.rows_written,insert_rows_per_second=EXCLUDED.insert_rows_per_second,insert_latency_ms=EXCLUDED.insert_latency_ms,
              replication_catchup_ms=EXCLUDED.replication_catchup_ms,max_replication_delay_seconds=EXCLUDED.max_replication_delay_seconds,
              max_replication_queue=EXCLUDED.max_replication_queue,max_log_lag=EXCLUDED.max_log_lag,consistency_passed=EXCLUDED.consistency_passed,
              replica_count=EXCLUDED.replica_count,message=EXCLUDED.message
            """, s.id(), s.profileId(), s.scenario(), s.table(), s.sourceEndpoint(), s.status(), s.createdAt(), s.startedAt(), s.finishedAt(),
                s.rowsWritten(), s.insertRowsPerSecond(), s.insertLatencyMs(), s.replicationCatchupMs(), s.maxReplicationDelaySeconds(),
                s.maxReplicationQueue(), s.maxLogLag(), s.consistencyPassed(), s.replicaCount(), s.message());
    }

    private Snapshot map(ResultSet rs) throws java.sql.SQLException {
        return new Snapshot(rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class), rs.getString("scenario"),
                rs.getString("table_name"), rs.getString("source_endpoint"), rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                instant(rs,"started_at"), instant(rs,"finished_at"), rs.getLong("rows_written"), rs.getDouble("insert_rows_per_second"),
                rs.getDouble("insert_latency_ms"), rs.getLong("replication_catchup_ms"), rs.getLong("max_replication_delay_seconds"),
                rs.getLong("max_replication_queue"), rs.getLong("max_log_lag"), (Boolean)rs.getObject("consistency_passed"), rs.getInt("replica_count"), rs.getString("message"));
    }

    private static Instant instant(ResultSet rs, String name) throws java.sql.SQLException {
        java.time.OffsetDateTime v = rs.getObject(name, java.time.OffsetDateTime.class); return v == null ? null : v.toInstant();
    }
    private static void validate(Request r) {
        if (r == null || r.profileId() == null) throw new IllegalArgumentException("profileId is required");
        if (!List.of("REPLICATED_INSERT","REPLICATION_CATCHUP","REPLICA_CONSISTENCY").contains(r.scenario().toUpperCase())) throw new IllegalArgumentException("Unsupported scenario");
        table(r.table());
        if (r.rows() < 1 || r.batchSize() < 1 || r.payloadBytes() < 1) throw new IllegalArgumentException("rows, batchSize and payloadBytes must be positive");
    }
    private static String table(String value) {
        String v = value == null ? "" : value.trim();
        if (!v.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid ClickHouse table name");
        return v;
    }
    private static String rootMessage(Throwable e) { Throwable c=e; while(c.getCause()!=null)c=c.getCause(); return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage(); }

    public record Request(UUID profileId, String scenario, String table, String sourceEndpoint, long rows, int batchSize,
                          int payloadBytes, long catchupTimeoutSeconds, long pollIntervalMs) { }
    public record Snapshot(UUID id, UUID profileId, String scenario, String table, String sourceEndpoint, String status,
                           Instant createdAt, Instant startedAt, Instant finishedAt, long rowsWritten, double insertRowsPerSecond,
                           double insertLatencyMs, long replicationCatchupMs, long maxReplicationDelaySeconds,
                           long maxReplicationQueue, long maxLogLag, Boolean consistencyPassed, int replicaCount, String message) { }
    private record ReplicaDigest(String endpoint, long rows, String sequenceSum, String payloadBytes) { }
    private static final class Run {
        final UUID id; final Request request; final String sourceEndpoint; final Instant createdAt;
        volatile String status="QUEUED"; volatile Instant startedAt; volatile Instant finishedAt; volatile long rowsWritten;
        volatile double insertRowsPerSecond; volatile double insertLatencyMs; volatile long replicationCatchupMs;
        volatile long maxReplicationDelaySeconds; volatile long maxReplicationQueue; volatile long maxLogLag;
        volatile Boolean consistencyPassed; volatile int replicaCount; volatile String message="Queued";
        Run(UUID id, Request request, String sourceEndpoint, Instant createdAt){this.id=id;this.request=request;this.sourceEndpoint=sourceEndpoint;this.createdAt=createdAt;}
        Snapshot snapshot(){return new Snapshot(id,request.profileId(),request.scenario().toUpperCase(),request.table(),sourceEndpoint,status,createdAt,startedAt,finishedAt,
                rowsWritten,insertRowsPerSecond,insertLatencyMs,replicationCatchupMs,maxReplicationDelaySeconds,maxReplicationQueue,maxLogLag,consistencyPassed,replicaCount,message);}
    }
}
