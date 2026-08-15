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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseReplicaFailoverService {
    private final ClickHouseProfileService profiles;
    private final ClickHouseReplicationObservabilityService observability;
    private final JdbcTemplate jdbc;
    private final Executor executor;
    private final Map<UUID, Run> active = new ConcurrentHashMap<>();

    public ClickHouseReplicaFailoverService(ClickHouseProfileService profiles,
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
            run.message = "Cannot schedule failover scenario: " + rootMessage(e);
            persist(run.snapshot());
            throw e;
        }
        return run.snapshot();
    }

    public Snapshot confirmFault(UUID id) {
        Run run = requireActive(id);
        if (!"WAITING_FOR_FAULT".equals(run.status)) throw new IllegalStateException("Run is not waiting for fault confirmation");
        run.faultConfirmedAt = Instant.now();
        run.faultConfirmed.set(true);
        run.message = "Fault confirmed; workload continues during degraded state";
        persist(run.snapshot());
        return run.snapshot();
    }

    public Snapshot startRecovery(UUID id) {
        Run run = requireActive(id);
        if (!"WAITING_FOR_RECOVERY".equals(run.status)) throw new IllegalStateException("Run is not waiting for recovery");
        run.recoveryStartedAt = Instant.now();
        run.recoveryConfirmed.set(true);
        run.message = "Recovery confirmed; waiting for replicas to catch up";
        persist(run.snapshot());
        return run.snapshot();
    }

    public Snapshot get(UUID id) {
        Run run = active.get(id);
        if (run != null) return run.snapshot();
        List<Snapshot> rows = jdbc.query("SELECT * FROM clickhouse_failover_run WHERE id=?", (rs,n) -> map(rs), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Failover run not found: " + id);
        return rows.getFirst();
    }

    public List<Snapshot> list(UUID profileId, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        if (profileId == null) return jdbc.query("SELECT * FROM clickhouse_failover_run ORDER BY created_at DESC LIMIT ?", (rs,n)->map(rs), safe);
        return jdbc.query("SELECT * FROM clickhouse_failover_run WHERE profile_id=? ORDER BY created_at DESC LIMIT ?", (rs,n)->map(rs), profileId, safe);
    }

    private void execute(Run run) {
        run.startedAt = Instant.now();
        run.status = "RUNNING_BASELINE";
        run.message = "Baseline workload is running";
        persist(run.snapshot());
        Thread workload = Thread.ofVirtual().start(() -> workload(run));
        try {
            sleepSeconds(Math.max(1, run.request.baselineSeconds()));
            run.status = "WAITING_FOR_FAULT";
            run.message = "Apply external replica fault, then confirm it";
            persist(run.snapshot());
            waitFor(run.faultConfirmed, run.request.faultConfirmationTimeoutSeconds(), "Fault confirmation timeout");

            run.status = "FAULT_ACTIVE";
            Instant faultStarted = run.faultConfirmedAt == null ? Instant.now() : run.faultConfirmedAt;
            Instant observationEnd = faultStarted.plusSeconds(Math.max(1, run.request.faultObservationSeconds()));
            boolean degradedSeen = false;
            while (Instant.now().isBefore(observationEnd)) {
                ClickHouseReplicationObservabilityService.Snapshot snapshot = observability.snapshot(run.request.profileId());
                updateReplicationMetrics(run, snapshot);
                degradedSeen |= snapshot.nodes().stream().anyMatch(n -> !n.reachable() || !"OK".equals(n.health().status()));
                Thread.sleep(Math.max(200, run.request.pollIntervalMs()));
            }
            if (!degradedSeen) throw new IllegalStateException("Replica fault was not detected by observability");

            run.status = "WAITING_FOR_RECOVERY";
            run.message = "Restore the failed replica, then confirm recovery start";
            persist(run.snapshot());
            waitFor(run.recoveryConfirmed, run.request.recoveryConfirmationTimeoutSeconds(), "Recovery confirmation timeout");

            run.status = "RECOVERING";
            Instant recoveryStart = run.recoveryStartedAt == null ? Instant.now() : run.recoveryStartedAt;
            waitForHealthy(run, run.request.recoveryTimeoutSeconds());
            run.recoveryTimeMs = Duration.between(recoveryStart, Instant.now()).toMillis();
            run.serviceInterruptionMs = run.firstFailureAt == null ? 0
                    : Duration.between(run.firstFailureAt, run.lastFailureAt == null ? run.firstFailureAt : run.lastFailureAt).toMillis();
            consistency(run);
            run.status = run.consistencyPassed ? "COMPLETED" : "FAILED";
            run.message = run.consistencyPassed ? "Failover scenario completed" : "Replica consistency check failed after recovery";
        } catch (Exception e) {
            run.status = "FAILED";
            run.message = rootMessage(e);
        } finally {
            run.stop.set(true);
            try { workload.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            run.finishedAt = Instant.now();
            persist(run.snapshot());
            active.remove(run.id);
        }
    }

    private void workload(Run run) {
        byte[] bytes = new byte[run.request.payloadBytes()];
        java.util.Arrays.fill(bytes, (byte)'x');
        String payload = new String(bytes, StandardCharsets.US_ASCII);
        long sequence = System.currentTimeMillis() * 1000L;
        while (!run.stop.get()) {
            try (Connection c = profiles.open(run.request.profileId(), run.sourceEndpoint);
                 PreparedStatement ps = c.prepareStatement("INSERT INTO " + table(run.request.table())
                         + " (event_time, sequence, payload) VALUES (now64(3), ?, ?)")) {
                for (int i=0; i<run.request.batchSize() && !run.stop.get(); i++) {
                    ps.setLong(1, sequence + run.rowsWritten.get() + i);
                    ps.setString(2, payload);
                    ps.addBatch();
                }
                ps.executeBatch();
                run.rowsWritten.addAndGet(run.request.batchSize());
            } catch (Exception e) {
                run.failedOperations.incrementAndGet();
                Instant now = Instant.now();
                if (run.firstFailureAt == null) run.firstFailureAt = now;
                run.lastFailureAt = now;
                try { Thread.sleep(Math.max(100, run.request.pollIntervalMs())); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void waitForHealthy(Run run, long timeoutSeconds) throws Exception {
        Instant started = Instant.now();
        while (Duration.between(started, Instant.now()).getSeconds() <= Math.max(1, timeoutSeconds)) {
            ClickHouseReplicationObservabilityService.Snapshot snapshot = observability.snapshot(run.request.profileId());
            updateReplicationMetrics(run, snapshot);
            boolean healthy = snapshot.nodes().stream().allMatch(n -> n.reachable() && "OK".equals(n.health().status())
                    && n.health().queueSize() == 0 && n.health().maxLogLag() == 0 && n.health().maxAbsoluteDelaySeconds() == 0);
            if (healthy) return;
            Thread.sleep(Math.max(200, run.request.pollIntervalMs()));
        }
        throw new IllegalStateException("Replica did not recover within " + timeoutSeconds + " seconds");
    }

    private static void updateReplicationMetrics(Run run, ClickHouseReplicationObservabilityService.Snapshot snapshot) {
        long delay = snapshot.nodes().stream().mapToLong(n -> n.health().maxAbsoluteDelaySeconds()).max().orElse(0);
        long queue = snapshot.nodes().stream().mapToLong(n -> n.health().queueSize()).max().orElse(0);
        long lag = snapshot.nodes().stream().mapToLong(n -> n.health().maxLogLag()).max().orElse(0);
        run.maxReplicationDelaySeconds = Math.max(run.maxReplicationDelaySeconds, delay);
        run.maxReplicationQueue = Math.max(run.maxReplicationQueue, queue);
        run.maxLogLag = Math.max(run.maxLogLag, lag);
    }

    private void consistency(Run run) throws Exception {
        ClickHouseProfileService.Profile profile = profiles.get(run.request.profileId());
        List<Digest> digests = new ArrayList<>();
        for (String endpoint : profile.endpoints()) {
            try (Connection c = profiles.open(run.request.profileId(), endpoint);
                 PreparedStatement ps = c.prepareStatement("SELECT count(), sum(sequence), sum(length(payload)) FROM " + table(run.request.table()));
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) digests.add(new Digest(endpoint, rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
        }
        run.replicaCount = digests.size();
        if (digests.isEmpty()) throw new IllegalStateException("No replicas available after recovery");
        Digest first = digests.getFirst();
        run.consistencyPassed = digests.stream().allMatch(d -> d.rows == first.rows
                && java.util.Objects.equals(d.sequenceSum, first.sequenceSum)
                && java.util.Objects.equals(d.payloadBytes, first.payloadBytes));
    }

    private static void waitFor(AtomicBoolean flag, long timeoutSeconds, String timeoutMessage) throws InterruptedException {
        Instant started = Instant.now();
        while (!flag.get() && Duration.between(started, Instant.now()).getSeconds() <= Math.max(1, timeoutSeconds)) Thread.sleep(250);
        if (!flag.get()) throw new IllegalStateException(timeoutMessage);
    }
    private static void sleepSeconds(long seconds) throws InterruptedException { Thread.sleep(seconds * 1000L); }
    private Run requireActive(UUID id) { Run run = active.get(id); if (run == null) throw new IllegalArgumentException("Active failover run not found: " + id); return run; }

    private void persist(Snapshot s) {
        jdbc.update("""
            INSERT INTO clickhouse_failover_run(id,profile_id,table_name,source_endpoint,status,created_at,started_at,fault_confirmed_at,recovery_started_at,finished_at,
              rows_written,failed_operations,max_replication_delay_seconds,max_replication_queue,max_log_lag,service_interruption_ms,recovery_time_ms,consistency_passed,replica_count,message)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status,started_at=EXCLUDED.started_at,fault_confirmed_at=EXCLUDED.fault_confirmed_at,
              recovery_started_at=EXCLUDED.recovery_started_at,finished_at=EXCLUDED.finished_at,rows_written=EXCLUDED.rows_written,
              failed_operations=EXCLUDED.failed_operations,max_replication_delay_seconds=EXCLUDED.max_replication_delay_seconds,
              max_replication_queue=EXCLUDED.max_replication_queue,max_log_lag=EXCLUDED.max_log_lag,service_interruption_ms=EXCLUDED.service_interruption_ms,
              recovery_time_ms=EXCLUDED.recovery_time_ms,consistency_passed=EXCLUDED.consistency_passed,replica_count=EXCLUDED.replica_count,message=EXCLUDED.message
            """, s.id(),s.profileId(),s.table(),s.sourceEndpoint(),s.status(),
                ClickHouseJdbcTime.timestamptz(s.createdAt()), ClickHouseJdbcTime.timestamptz(s.startedAt()),
                ClickHouseJdbcTime.timestamptz(s.faultConfirmedAt()), ClickHouseJdbcTime.timestamptz(s.recoveryStartedAt()),
                ClickHouseJdbcTime.timestamptz(s.finishedAt()),
                s.rowsWritten(),s.failedOperations(),s.maxReplicationDelaySeconds(),s.maxReplicationQueue(),s.maxLogLag(),s.serviceInterruptionMs(),s.recoveryTimeMs(),
                s.consistencyPassed(),s.replicaCount(),s.message());
    }

    private Snapshot map(ResultSet rs) throws java.sql.SQLException {
        return new Snapshot(rs.getObject("id",UUID.class),rs.getObject("profile_id",UUID.class),rs.getString("table_name"),rs.getString("source_endpoint"),rs.getString("status"),
                instant(rs,"created_at"),instant(rs,"started_at"),instant(rs,"fault_confirmed_at"),instant(rs,"recovery_started_at"),instant(rs,"finished_at"),
                rs.getLong("rows_written"),rs.getLong("failed_operations"),rs.getLong("max_replication_delay_seconds"),rs.getLong("max_replication_queue"),rs.getLong("max_log_lag"),
                rs.getLong("service_interruption_ms"),rs.getLong("recovery_time_ms"),(Boolean)rs.getObject("consistency_passed"),rs.getInt("replica_count"),rs.getString("message"));
    }
    private static Instant instant(ResultSet rs,String column)throws java.sql.SQLException{java.time.OffsetDateTime v=rs.getObject(column,java.time.OffsetDateTime.class);return v==null?null:v.toInstant();}
    private static void validate(Request r){if(r==null||r.profileId()==null)throw new IllegalArgumentException("profileId is required");table(r.table());if(r.batchSize()<1||r.payloadBytes()<1)throw new IllegalArgumentException("batchSize and payloadBytes must be positive");}
    private static String table(String value){String v=value==null?"":value.trim();if(!v.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Invalid ClickHouse table name");return v;}
    private static String rootMessage(Throwable e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}

    public record Request(UUID profileId,String table,String sourceEndpoint,int batchSize,int payloadBytes,long baselineSeconds,
                          long faultConfirmationTimeoutSeconds,long faultObservationSeconds,long recoveryConfirmationTimeoutSeconds,
                          long recoveryTimeoutSeconds,long pollIntervalMs) { }
    public record Snapshot(UUID id,UUID profileId,String table,String sourceEndpoint,String status,Instant createdAt,Instant startedAt,
                           Instant faultConfirmedAt,Instant recoveryStartedAt,Instant finishedAt,long rowsWritten,long failedOperations,
                           long maxReplicationDelaySeconds,long maxReplicationQueue,long maxLogLag,long serviceInterruptionMs,long recoveryTimeMs,
                           Boolean consistencyPassed,int replicaCount,String message) { }
    private record Digest(String endpoint,long rows,String sequenceSum,String payloadBytes) { }
    private static final class Run {
        final UUID id; final Request request; final String sourceEndpoint; final Instant createdAt;
        final AtomicBoolean stop=new AtomicBoolean(); final AtomicBoolean faultConfirmed=new AtomicBoolean(); final AtomicBoolean recoveryConfirmed=new AtomicBoolean();
        final AtomicLong rowsWritten=new AtomicLong(); final AtomicLong failedOperations=new AtomicLong();
        volatile String status="QUEUED"; volatile Instant startedAt; volatile Instant faultConfirmedAt; volatile Instant recoveryStartedAt; volatile Instant finishedAt;
        volatile Instant firstFailureAt; volatile Instant lastFailureAt; volatile long maxReplicationDelaySeconds; volatile long maxReplicationQueue; volatile long maxLogLag;
        volatile long serviceInterruptionMs; volatile long recoveryTimeMs; volatile boolean consistencyPassed; volatile int replicaCount; volatile String message="Queued";
        Run(UUID id,Request request,String sourceEndpoint,Instant createdAt){this.id=id;this.request=request;this.sourceEndpoint=sourceEndpoint;this.createdAt=createdAt;}
        Snapshot snapshot(){return new Snapshot(id,request.profileId(),request.table(),sourceEndpoint,status,createdAt,startedAt,faultConfirmedAt,recoveryStartedAt,finishedAt,
                rowsWritten.get(),failedOperations.get(),maxReplicationDelaySeconds,maxReplicationQueue,maxLogLag,serviceInterruptionMs,recoveryTimeMs,consistencyPassed,replicaCount,message);}
    }
}
