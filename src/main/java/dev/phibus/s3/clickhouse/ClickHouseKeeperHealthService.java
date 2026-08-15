package dev.phibus.s3.clickhouse;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseKeeperHealthService {
    private final ClickHouseProfileService profiles;
    private final MeterRegistry meterRegistry;
    private final Map<String, MetricsState> metrics = new ConcurrentHashMap<>();

    public ClickHouseKeeperHealthService(ClickHouseProfileService profiles, MeterRegistry meterRegistry) {
        this.profiles = profiles;
        this.meterRegistry = meterRegistry;
    }

    public Snapshot snapshot(UUID profileId) {
        ClickHouseProfileService.Profile profile = profiles.get(profileId);
        List<Node> nodes = new ArrayList<>();
        for (String endpoint : profile.endpoints()) {
            nodes.add(probe(profile, endpoint));
        }
        String status = nodes.stream().anyMatch(n -> !n.connected()) ? "CRITICAL"
                : nodes.stream().anyMatch(n -> n.sessionErrors() > 0) ? "WARNING" : "OK";
        return new Snapshot(profileId, profile.name(), profile.database(), Instant.now(), status, List.copyOf(nodes));
    }

    private Node probe(ClickHouseProfileService.Profile profile, String endpoint) {
        Instant started = Instant.now();
        boolean connected = false;
        long children = 0;
        long leaderReplicas = 0;
        long sessionErrors = 0;
        String error = null;
        try (Connection connection = profiles.open(profile.id(), endpoint)) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT count() FROM system.zookeeper WHERE path='/'")) {
                if (rs.next()) children = rs.getLong(1);
                connected = true;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT countIf(is_leader), countIf(is_session_expired OR zookeeper_exception!='') FROM system.replicas WHERE database='"
                         + safeDatabase(profile.database()) + "'")) {
                if (rs.next()) {
                    leaderReplicas = rs.getLong(1);
                    sessionErrors = rs.getLong(2);
                }
            }
        } catch (Exception e) {
            error = rootMessage(e);
        }
        long latencyMs = Duration.between(started, Instant.now()).toMillis();
        Node node = new Node(endpoint, connected, latencyMs, children, leaderReplicas, sessionErrors, error);
        updateMetrics(profile, node);
        return node;
    }

    private void updateMetrics(ClickHouseProfileService.Profile profile, Node node) {
        String key = profile.id() + "|" + node.endpoint();
        MetricsState state = metrics.computeIfAbsent(key, ignored -> register(profile, node.endpoint()));
        state.up.set(node.connected() ? 1.0 : 0.0);
        state.latencyMs.set((double) node.latencyMs());
        state.sessionErrors.set((double) node.sessionErrors());
        state.leaderReplicas.set((double) node.leaderReplicas());
    }

    private MetricsState register(ClickHouseProfileService.Profile profile, String endpoint) {
        MetricsState state = new MetricsState();
        Gauge.builder("s3perf_clickhouse_keeper_up", state.up, AtomicReference::get)
                .tag("profile", profile.name()).tag("database", profile.database()).tag("endpoint", endpoint).register(meterRegistry);
        Gauge.builder("s3perf_clickhouse_keeper_query_latency_ms", state.latencyMs, AtomicReference::get)
                .tag("profile", profile.name()).tag("database", profile.database()).tag("endpoint", endpoint).register(meterRegistry);
        Gauge.builder("s3perf_clickhouse_keeper_session_errors", state.sessionErrors, AtomicReference::get)
                .tag("profile", profile.name()).tag("database", profile.database()).tag("endpoint", endpoint).register(meterRegistry);
        Gauge.builder("s3perf_clickhouse_keeper_replica_leaders", state.leaderReplicas, AtomicReference::get)
                .tag("profile", profile.name()).tag("database", profile.database()).tag("endpoint", endpoint).register(meterRegistry);
        return state;
    }

    private static String safeDatabase(String database) {
        String value = database == null ? "default" : database.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid ClickHouse database name");
        return value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record Snapshot(UUID profileId, String profileName, String database, Instant collectedAt,
                           String status, List<Node> nodes) { }
    public record Node(String endpoint, boolean connected, long latencyMs, long rootChildren,
                       long leaderReplicas, long sessionErrors, String error) { }

    private static final class MetricsState {
        final AtomicReference<Double> up = new AtomicReference<>(0.0);
        final AtomicReference<Double> latencyMs = new AtomicReference<>(0.0);
        final AtomicReference<Double> sessionErrors = new AtomicReference<>(0.0);
        final AtomicReference<Double> leaderReplicas = new AtomicReference<>(0.0);
    }
}
