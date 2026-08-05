package dev.phibus.s3.distributed;

import dev.phibus.s3.test.TestRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class DistributedTestService {
    private final AgentRegistry registry;
    private final Map<UUID, DistributedRun> runs = new ConcurrentHashMap<>();
    private final Map<UUID, Assignment> assignments = new ConcurrentHashMap<>();

    public DistributedTestService(AgentRegistry registry) { this.registry = registry; }

    public DistributedRunView create(CreateDistributedTestRequest request) {
        if (request.agentIds() == null || request.agentIds().isEmpty()) throw new IllegalArgumentException("At least one agent is required");
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<UUID, AgentProgress> agents = new LinkedHashMap<>();
        for (UUID agentId : request.agentIds()) {
            registry.requireOnline(agentId);
            AgentProgress progress = new AgentProgress(agentId, "ASSIGNED", 0, 0, 0, 0, 0, 0, null, now);
            agents.put(agentId, progress);
            assignments.put(agentId, new Assignment(runId, agentId, request.testRequest(), now));
            registry.markBusy(agentId, true);
        }
        DistributedRun run = new DistributedRun(runId, request.name(), request.testRequest(), now, null,
                "RUNNING", new ConcurrentHashMap<>(agents));
        runs.put(runId, run);
        return view(run);
    }

    public Assignment poll(UUID agentId, String token) {
        registry.authenticate(agentId, token);
        return assignments.get(agentId);
    }

    public DistributedRunView report(UUID agentId, String token, AgentStatistics stats) {
        registry.authenticate(agentId, token);
        Assignment assignment = assignments.get(agentId);
        if (assignment == null || !assignment.runId().equals(stats.runId())) throw new IllegalArgumentException("No matching assignment");
        DistributedRun run = require(stats.runId());
        AgentProgress progress = new AgentProgress(agentId, stats.status(), stats.completedOperations(), stats.bytesTransferred(),
                stats.operationsPerSecond(), stats.throughputMiBps(), stats.p95LatencyMs(), stats.errors(), stats.message(), Instant.now());
        run.agents().put(agentId, progress);
        if (isTerminal(stats.status())) {
            assignments.remove(agentId);
            registry.markBusy(agentId, false);
        }
        if (run.agents().values().stream().allMatch(p -> isTerminal(p.status()))) {
            String status = run.agents().values().stream().anyMatch(p -> "FAILED".equals(p.status())) ? "FAILED" : "COMPLETED";
            runs.put(run.id(), new DistributedRun(run.id(), run.name(), run.request(), run.startedAt(), Instant.now(), status, run.agents()));
        }
        return get(run.id());
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
        double p95 = agents.stream().filter(a -> a.completedOperations() > 0)
                .mapToDouble(a -> a.p95LatencyMs() * a.completedOperations()).sum() / Math.max(1, operations);
        return new DistributedRunView(run.id(), run.name(), run.status(), run.startedAt(), run.finishedAt(),
                run.request(), operations, bytes, ops, throughput, p95, errors,
                agents.stream().sorted(Comparator.comparing(AgentProgress::agentId)).toList());
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    public record CreateDistributedTestRequest(String name, List<UUID> agentIds, TestRequest testRequest) { }
    public record Assignment(UUID runId, UUID agentId, TestRequest testRequest, Instant assignedAt) { }
    public record AgentStatistics(UUID runId, String status, long completedOperations, long bytesTransferred,
                                  double operationsPerSecond, double throughputMiBps, double p95LatencyMs,
                                  long errors, String message) { }
    public record AgentProgress(UUID agentId, String status, long completedOperations, long bytesTransferred,
                                double operationsPerSecond, double throughputMiBps, double p95LatencyMs,
                                long errors, String message, Instant updatedAt) { }
    private record DistributedRun(UUID id, String name, TestRequest request, Instant startedAt, Instant finishedAt,
                                  String status, Map<UUID, AgentProgress> agents) { }
    public record DistributedRunView(UUID id, String name, String status, Instant startedAt, Instant finishedAt,
                                     TestRequest testRequest, long completedOperations, long bytesTransferred,
                                     double operationsPerSecond, double throughputMiBps, double p95LatencyMs,
                                     long errors, List<AgentProgress> agents) { }
}
