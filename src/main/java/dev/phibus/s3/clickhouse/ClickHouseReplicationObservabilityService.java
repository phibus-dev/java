package dev.phibus.s3.clickhouse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseReplicationObservabilityService {
    private final ClickHouseProfileService profiles;

    public ClickHouseReplicationObservabilityService(ClickHouseProfileService profiles) { this.profiles = profiles; }

    public Snapshot snapshot(UUID profileId) {
        ClickHouseProfileService.Profile profile = profiles.get(profileId);
        List<NodeSnapshot> nodes = new ArrayList<>();
        for (String endpoint : profile.endpoints()) nodes.add(snapshotNode(profileId, endpoint, profile.database()));
        return new Snapshot(profileId, profile.name(), profile.database(), Instant.now(), List.copyOf(nodes));
    }

    private NodeSnapshot snapshotNode(UUID profileId, String endpoint, String database) {
        try (Connection connection = profiles.open(profileId, endpoint)) {
            List<Replica> replicas = replicas(connection, database);
            List<QueueItem> queue = queue(connection, database);
            List<PartSummary> parts = parts(connection, database);
            List<Merge> merges = merges(connection, database);
            List<Mutation> mutations = mutations(connection, database);
            Health health = health(replicas, queue, mutations);
            return new NodeSnapshot(endpoint, true, health, replicas, queue, parts, merges, mutations, null);
        } catch (Exception e) {
            return new NodeSnapshot(endpoint, false, new Health("CRITICAL", 0, 0, 0, 0, 0, 0),
                    List.of(), List.of(), List.of(), List.of(), List.of(), rootMessage(e));
        }
    }

    private static List<Replica> replicas(Connection c, String db) throws SQLException {
        String sql = "SELECT table, engine, is_leader, is_readonly, is_session_expired, future_parts, parts_to_check, " +
                "queue_size, inserts_in_queue, merges_in_queue, log_max_index-log_pointer AS log_lag, " +
                "absolute_delay, total_replicas, active_replicas, last_queue_update_exception, zookeeper_exception " +
                "FROM system.replicas WHERE database=? ORDER BY table";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, db); try (ResultSet r = s.executeQuery()) { List<Replica> out = new ArrayList<>();
                while (r.next()) out.add(new Replica(r.getString(1), r.getString(2), r.getBoolean(3), r.getBoolean(4),
                        r.getBoolean(5), r.getLong(6), r.getLong(7), r.getLong(8), r.getLong(9), r.getLong(10),
                        r.getLong(11), r.getLong(12), r.getLong(13), r.getLong(14), r.getString(15), r.getString(16)));
                return List.copyOf(out); }
        }
    }

    private static List<QueueItem> queue(Connection c, String db) throws SQLException {
        String sql = "SELECT table, type, create_time, num_tries, num_postponed, postpone_reason, last_exception, " +
                "if(source_replica='', '', source_replica) FROM system.replication_queue WHERE database=? " +
                "ORDER BY create_time ASC LIMIT 500";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, db); try (ResultSet r = s.executeQuery()) { List<QueueItem> out = new ArrayList<>();
                while (r.next()) out.add(new QueueItem(r.getString(1), r.getString(2), r.getObject(3).toString(),
                        r.getLong(4), r.getLong(5), r.getString(6), r.getString(7), r.getString(8)));
                return List.copyOf(out); }
        }
    }

    private static List<PartSummary> parts(Connection c, String db) throws SQLException {
        String sql = "SELECT table, countIf(active), sumIf(rows,active), sumIf(bytes_on_disk,active), " +
                "countIf(NOT active) FROM system.parts WHERE database=? GROUP BY table ORDER BY table";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, db); try (ResultSet r = s.executeQuery()) { List<PartSummary> out = new ArrayList<>();
                while (r.next()) out.add(new PartSummary(r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5)));
                return List.copyOf(out); }
        }
    }

    private static List<Merge> merges(Connection c, String db) throws SQLException {
        String sql = "SELECT table, elapsed, progress, num_parts, total_size_bytes_compressed, result_part_name " +
                "FROM system.merges WHERE database=? ORDER BY elapsed DESC";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, db); try (ResultSet r = s.executeQuery()) { List<Merge> out = new ArrayList<>();
                while (r.next()) out.add(new Merge(r.getString(1), r.getDouble(2), r.getDouble(3), r.getLong(4), r.getLong(5), r.getString(6)));
                return List.copyOf(out); }
        }
    }

    private static List<Mutation> mutations(Connection c, String db) throws SQLException {
        String sql = "SELECT table, mutation_id, command, create_time, is_done, parts_to_do, latest_fail_reason " +
                "FROM system.mutations WHERE database=? AND (NOT is_done OR latest_fail_reason!='') ORDER BY create_time ASC LIMIT 500";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, db); try (ResultSet r = s.executeQuery()) { List<Mutation> out = new ArrayList<>();
                while (r.next()) out.add(new Mutation(r.getString(1), r.getString(2), r.getString(3), r.getObject(4).toString(),
                        r.getBoolean(5), r.getLong(6), r.getString(7)));
                return List.copyOf(out); }
        }
    }

    private static Health health(List<Replica> replicas, List<QueueItem> queue, List<Mutation> mutations) {
        long readonly = replicas.stream().filter(Replica::readOnly).count();
        long expired = replicas.stream().filter(Replica::sessionExpired).count();
        long inactive = replicas.stream().mapToLong(r -> Math.max(0, r.totalReplicas()-r.activeReplicas())).sum();
        long maxDelay = replicas.stream().mapToLong(Replica::absoluteDelaySeconds).max().orElse(0);
        long maxLag = replicas.stream().mapToLong(Replica::logLag).max().orElse(0);
        long mutationFailures = mutations.stream().filter(m -> m.latestFailReason()!=null && !m.latestFailReason().isBlank()).count();
        String status = readonly > 0 || expired > 0 || inactive > 0 || mutationFailures > 0 ? "CRITICAL"
                : maxDelay > 60 || maxLag > 100 || queue.size() > 100 ? "WARNING" : "OK";
        return new Health(status, readonly, expired, inactive, queue.size(), maxDelay, maxLag);
    }

    private static String rootMessage(Throwable error) { Throwable c=error; while(c.getCause()!=null)c=c.getCause(); return c.getMessage(); }

    public record Snapshot(UUID profileId, String profileName, String database, Instant collectedAt, List<NodeSnapshot> nodes) { }
    public record NodeSnapshot(String endpoint, boolean reachable, Health health, List<Replica> replicas,
                               List<QueueItem> replicationQueue, List<PartSummary> parts, List<Merge> merges,
                               List<Mutation> mutations, String error) { }
    public record Health(String status, long readonlyReplicas, long expiredSessions, long inactiveReplicas,
                         long queueSize, long maxAbsoluteDelaySeconds, long maxLogLag) { }
    public record Replica(String table, String engine, boolean leader, boolean readOnly, boolean sessionExpired,
                          long futureParts, long partsToCheck, long queueSize, long insertsInQueue, long mergesInQueue,
                          long logLag, long absoluteDelaySeconds, long totalReplicas, long activeReplicas,
                          String lastQueueUpdateException, String zookeeperException) { }
    public record QueueItem(String table, String type, String createTime, long tries, long postponed,
                            String postponeReason, String lastException, String sourceReplica) { }
    public record PartSummary(String table, long activeParts, long rows, long bytesOnDisk, long inactiveParts) { }
    public record Merge(String table, double elapsedSeconds, double progress, long parts, long compressedBytes, String resultPart) { }
    public record Mutation(String table, String mutationId, String command, String createTime, boolean done,
                           long partsToDo, String latestFailReason) { }
}
