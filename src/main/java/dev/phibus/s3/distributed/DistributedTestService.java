package dev.phibus.s3.distributed;

import dev.phibus.s3.clickhouse.ClickHouseConnectionSpec;
import dev.phibus.s3.clickhouse.ClickHouseHistoryStore;
import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class DistributedTestService {
    private final AgentRegistry registry;
    private final ClickHouseProfileService clickHouseProfiles;
    private final ClickHouseHistoryStore clickHouseHistory;
    private final Map<UUID, DistributedRun> runs = new ConcurrentHashMap<>();
    private final Map<UUID, Assignment> assignments = new ConcurrentHashMap<>();

    public DistributedTestService(AgentRegistry registry, ClickHouseProfileService clickHouseProfiles,
                                  ClickHouseHistoryStore clickHouseHistory) {
        this.registry = registry;
        this.clickHouseProfiles = clickHouseProfiles;
        this.clickHouseHistory = clickHouseHistory;
    }

    public DistributedRunView create(CreateDistributedTestRequest request) {
        requireAgents(request.agentIds());
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<UUID, AgentProgress> agents = prepareAgents(request.agentIds(), TestType.S3);
        for (UUID agentId : request.agentIds()) {
            assignments.put(agentId, new Assignment(runId, agentId, TestType.S3, request.testRequest(), null, null, now));
        }
        DistributedRun run = new DistributedRun(runId, request.name(), TestType.S3, request.testRequest(), null,
                now, null, "RUNNING", new ConcurrentHashMap<>(agents), null);
        runs.put(runId, run);
        return view(run);
    }

    public DistributedRunView createClickHouse(CreateDistributedClickHouseTestRequest request) {
        requireAgents(request.agentIds());
        if (request.testRequest() == null) throw new IllegalArgumentException("ClickHouse test request is required");
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<UUID, AgentProgress> agents = prepareAgents(request.agentIds(), TestType.CLICKHOUSE);
        String firstEndpoint = null;
        for (UUID agentId : request.agentIds()) {
            String endpoint = request.endpointByAgent() == null ? request.testRequest().endpoint()
                    : request.endpointByAgent().getOrDefault(agentId, request.testRequest().endpoint());
            ClickHouseConnectionSpec spec = clickHouseProfiles.connectionSpec(request.testRequest().profileId(), endpoint);
            if (firstEndpoint == null) firstEndpoint = spec.endpoint();
            ClickHouseTestRequest agentRequest = new ClickHouseTestRequest(request.testRequest().profileId(), spec.endpoint(),
                    request.testRequest().table(), request.testRequest().operation(), request.testRequest().concurrency(),
                    request.testRequest().batchSize(), request.testRequest().rowCount(), request.testRequest().durationSeconds(),
                    request.testRequest().warmupSeconds(), request.testRequest().payloadBytes(), request.testRequest().autoCreateTable());
            assignments.put(agentId, new Assignment(runId, agentId, TestType.CLICKHOUSE, null, agentRequest, spec, now));
        }
        DistributedRun run = new DistributedRun(runId, request.name(), TestType.CLICKHOUSE, null, request.testRequest(),
                now, null, "RUNNING", new ConcurrentHashMap<>(agents), firstEndpoint);
        runs.put(runId, run);
        return view(run);
    }

    private Map<UUID, AgentProgress> prepareAgents(List<UUID> agentIds, TestType type) {
        Map<UUID, AgentProgress> agents = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (UUID agentId : agentIds) {
            if (type == TestType.CLICKHOUSE) registry.requireCapability(agentId, "CLICKHOUSE");
            else registry.requireOnline(agentId);
            agents.put(agentId, new AgentProgress(agentId, "ASSIGNED", 0, 0, 0, 0, 0, 0, 0, 0, null, now));
            registry.markBusy(agentId, true);
        }
        return agents;
    }

    public Assignment poll(UUID agentId, String token) {
        registry.authenticate(agentId, token);
        return assignments.get(agentId);
    }

    public DistributedRunView report(UUID agentId, String token, AgentStatistics stats) {
        registry.authenticate(agentId, token);
        Assignment assignment = assignments.get(agentId);
        if (assignment == null || !assignment.runId().equals(stats.runId()))
            throw new IllegalArgumentException("No matching assignment");
        DistributedRun run = require(stats.runId());
        AgentProgress progress = new AgentProgress(agentId, stats.status(), stats.completedOperations(), stats.bytesTransferred(),
                stats.operationsPerSecond(), stats.throughputMiBps(), stats.p50LatencyMs(), stats.p95LatencyMs(),
                stats.p99LatencyMs(), stats.errors(), stats.message(), Instant.now());
        run.agents().put(agentId, progress);
        if (isTerminal(stats.status())) {
            assignments.remove(agentId);
            registry.markBusy(agentId, false);
        }
        if (run.agents().values().stream().allMatch(p -> isTerminal(p.status())) && !isTerminal(run.status())) {
            String status = run.agents().values().stream().anyMatch(p -> "FAILED".equals(p.status())) ? "FAILED"
                    : run.agents().values().stream().anyMatch(p -> "CANCELLED".equals(p.status())) ? "CANCELLED" : "COMPLETED";
            DistributedRun completed = new DistributedRun(run.id(), run.name(), run.testType(), run.s3Request(),
                    run.clickHouseRequest(), run.startedAt(), Instant.now(), status, run.agents(), run.primaryEndpoint());
            runs.put(run.id(), completed);
            if (run.testType() == TestType.CLICKHOUSE) persistClickHouse(completed);
        }
        return get(run.id());
    }

    private void persistClickHouse(DistributedRun run) {
        DistributedRunView aggregate = view(run);
        ClickHouseTestRequest request = run.clickHouseRequest();
        clickHouseHistory.saveDistributed(run.id(), request, run.primaryEndpoint(), run.status(), run.startedAt(), run.finishedAt(),
                aggregate.completedOperations(), aggregate.bytesTransferred(), aggregate.completedQueries(), aggregate.errors(),
                aggregate.operationsPerSecond(), aggregate.throughputMiBps(), aggregate.queriesPerSecond(),
                aggregate.p50LatencyMs(), aggregate.p95LatencyMs(), aggregate.p99LatencyMs(),
                "Distributed ClickHouse test: " + run.agents().size() + " agents");
    }

    public List<DistributedRunView> list() {
        return runs.values().stream().sorted(Comparator.comparing(DistributedRun::startedAt).reversed()).map(this::view).toList();
    }
    public DistributedRunView get(UUID id) { return view(require(id)); }

    private DistributedRun require(UUID id) {
        DistributedRun run = runs.get(id);
        if (run == null) throw new IllegalArgumentException("Distributed test not found");
        return run;
    }

    private DistributedRunView view(DistributedRun run) {
        List<AgentProgress> agents = new ArrayList<>(run.agents().values());
        long operations = agents.stream().mapToLong(AgentProgress::completedOperations).sum();
        long bytes = agents.stream().mapToLong(AgentProgress::bytesTransferred).sum();
        double ops = agents.stream().mapToDouble(AgentProgress::operationsPerSecond).sum();
        double throughput = agents.stream().mapToDouble(AgentProgress::throughputMiBps).sum();
        long errors = agents.stream().mapToLong(AgentProgress::errors).sum();
        double p50 = weightedLatency(agents, operations, 50);
        double p95 = weightedLatency(agents, operations, 95);
        double p99 = weightedLatency(agents, operations, 99);
        long queries = run.testType() == TestType.CLICKHOUSE ? operations : 0;
        double qps = run.testType() == TestType.CLICKHOUSE ? ops : 0;
        return new DistributedRunView(run.id(), run.name(), run.testType(), run.status(), run.startedAt(), run.finishedAt(),
                run.s3Request(), run.clickHouseRequest(), operations, bytes, queries, ops, throughput, qps,
                p50, p95, p99, errors, agents.stream().sorted(Comparator.comparing(AgentProgress::agentId)).toList());
    }

    private static double weightedLatency(List<AgentProgress> agents, long operations, int percentile) {
        if (operations <= 0) return 0;
        return agents.stream().filter(a -> a.completedOperations() > 0).mapToDouble(a -> {
            double value = percentile == 50 ? a.p50LatencyMs() : percentile == 99 ? a.p99LatencyMs() : a.p95LatencyMs();
            return value * a.completedOperations();
        }).sum() / operations;
    }

    private static void requireAgents(List<UUID> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) throw new IllegalArgumentException("At least one agent is required");
    }
    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    public record CreateDistributedTestRequest(String name, List<UUID> agentIds, TestRequest testRequest) { }
    public record CreateDistributedClickHouseTestRequest(String name, List<UUID> agentIds,
                                                         ClickHouseTestRequest testRequest,
                                                         Map<UUID, String> endpointByAgent) { }
    public record Assignment(UUID runId, UUID agentId, TestType testType, TestRequest testRequest,
                             ClickHouseTestRequest clickHouseRequest, ClickHouseConnectionSpec clickHouseConnection,
                             Instant assignedAt) { }
    public record AgentStatistics(UUID runId, String status, long completedOperations, long bytesTransferred,
                                  double operationsPerSecond, double throughputMiBps, double p50LatencyMs,
                                  double p95LatencyMs, double p99LatencyMs, long errors, String message) { }
    public record AgentProgress(UUID agentId, String status, long completedOperations, long bytesTransferred,
                                double operationsPerSecond, double throughputMiBps, double p50LatencyMs,
                                double p95LatencyMs, double p99LatencyMs, long errors, String message, Instant updatedAt) { }
    private record DistributedRun(UUID id, String name, TestType testType, TestRequest s3Request,
                                  ClickHouseTestRequest clickHouseRequest, Instant startedAt, Instant finishedAt,
                                  String status, Map<UUID, AgentProgress> agents, String primaryEndpoint) { }
    public record DistributedRunView(UUID id, String name, TestType testType, String status, Instant startedAt,
                                     Instant finishedAt, TestRequest testRequest, ClickHouseTestRequest clickHouseRequest,
                                     long completedOperations, long bytesTransferred, long completedQueries,
                                     double operationsPerSecond, double throughputMiBps, double queriesPerSecond,
                                     double p50LatencyMs, double p95LatencyMs, double p99LatencyMs,
                                     long errors, List<AgentProgress> agents) { }
}
