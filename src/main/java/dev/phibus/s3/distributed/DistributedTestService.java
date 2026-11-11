package dev.phibus.s3.distributed;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.phibus.s3.clickhouse.ClickHouseConnectionSpec;
import dev.phibus.s3.clickhouse.ClickHouseHistoryStore;
import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.kafka.KafkaProfileService;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.test.LatencyHistogramCodec;
import dev.phibus.s3.test.S3LatencyScope;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.HdrHistogram.Histogram;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class DistributedTestService {
    private static final Set<String> AGENT_STATUSES = Set.of(
            "QUEUED", "RUNNING", "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED");

    private final AgentRegistry registry;
    private final ClickHouseProfileService clickHouseProfiles;
    private final ClickHouseHistoryStore clickHouseHistory;
    private final TestHistoryStore testHistory;
    private final KafkaProfileService kafkaProfiles;
    private final DistributedRunRepository repository;
    private final SettingsService settingsService;
    private final Duration recoveryTimeout;
    private final Map<UUID, DistributedRun> runs = new ConcurrentHashMap<>();
    private final Map<UUID, Assignment> assignments = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    @Autowired
    DistributedTestService(AgentRegistry registry, ClickHouseProfileService clickHouseProfiles,
                           ClickHouseHistoryStore clickHouseHistory, TestHistoryStore testHistory,
                           KafkaProfileService kafkaProfiles, DistributedRunRepository repository,
                           SettingsService settingsService,
                           @Value("${s3perf.distributed.recovery-timeout-seconds:300}") long recoveryTimeoutSeconds) {
        this(registry, clickHouseProfiles, clickHouseHistory, testHistory, kafkaProfiles, repository,
                settingsService, Duration.ofSeconds(Math.max(30, recoveryTimeoutSeconds)));
    }

    DistributedTestService(AgentRegistry registry, ClickHouseProfileService clickHouseProfiles,
                           ClickHouseHistoryStore clickHouseHistory, TestHistoryStore testHistory,
                           KafkaProfileService kafkaProfiles, DistributedRunRepository repository,
                           Duration recoveryTimeout) {
        this(registry, clickHouseProfiles, clickHouseHistory, testHistory, kafkaProfiles, repository,
                null, recoveryTimeout);
    }

    private DistributedTestService(AgentRegistry registry, ClickHouseProfileService clickHouseProfiles,
                                   ClickHouseHistoryStore clickHouseHistory, TestHistoryStore testHistory,
                                   KafkaProfileService kafkaProfiles, DistributedRunRepository repository,
                                   SettingsService settingsService, Duration recoveryTimeout) {
        this.registry = registry;
        this.clickHouseProfiles = clickHouseProfiles;
        this.clickHouseHistory = clickHouseHistory;
        this.testHistory = testHistory;
        this.kafkaProfiles = kafkaProfiles;
        this.repository = repository;
        this.settingsService = settingsService;
        this.recoveryTimeout = recoveryTimeout.isNegative() || recoveryTimeout.isZero()
                ? Duration.ofMinutes(5) : recoveryTimeout;
    }

    /** In-memory constructor retained for focused unit tests. */
    public DistributedTestService(AgentRegistry registry, ClickHouseProfileService clickHouseProfiles,
                                  ClickHouseHistoryStore clickHouseHistory, TestHistoryStore testHistory,
                                  KafkaProfileService kafkaProfiles) {
        this(registry, clickHouseProfiles, clickHouseHistory, testHistory, kafkaProfiles, null,
                null, Duration.ofMinutes(5));
        this.loaded = true;
    }

    public synchronized DistributedRunView create(CreateDistributedTestRequest request) {
        ensureLoaded();
        requireAgents(request.agentIds());
        if (request.testRequest() == null) throw new IllegalArgumentException("S3 test request is required");
        if (notBlank(request.testRequest().accessKey()) || notBlank(request.testRequest().secretKey())) {
            throw new IllegalArgumentException(
                    "Distributed S3 tests require credentials configured locally on each agent");
        }
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<UUID, AgentProgress> progress = prepareAgents(request.agentIds(), TestType.S3);
        try {
            Map<UUID, Assignment> definitions = new LinkedHashMap<>();
            for (UUID agentId : request.agentIds()) {
                definitions.put(agentId, new Assignment(runId, agentId, TestType.S3, request.testRequest(),
                        null, null, null, null, now));
            }
            DistributedRun run = new DistributedRun(runId, request.name(), TestType.S3, request.testRequest(),
                    null, null, now, now, null, now, "RUNNING", null, "Distributed test started",
                    Map.copyOf(progress), Map.copyOf(definitions), null);
            persistAndActivate(run);
            return view(run);
        } catch (RuntimeException error) {
            request.agentIds().forEach(registry::release);
            throw error;
        }
    }

    public synchronized DistributedRunView createClickHouse(CreateDistributedClickHouseTestRequest request) {
        ensureLoaded();
        requireAgents(request.agentIds());
        if (request.testRequest() == null) {
            throw new IllegalArgumentException("ClickHouse test request is required");
        }
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<UUID, ClickHouseConnectionSpec> connections = new LinkedHashMap<>();
        String firstEndpoint = null;
        for (UUID agentId : request.agentIds()) {
            String endpoint = request.endpointByAgent() == null ? request.testRequest().endpoint()
                    : request.endpointByAgent().getOrDefault(agentId, request.testRequest().endpoint());
            ClickHouseConnectionSpec spec = clickHouseProfiles.connectionSpec(
                    request.testRequest().profileId(), endpoint);
            if (firstEndpoint == null) firstEndpoint = spec.endpoint();
            connections.put(agentId, spec);
        }
        Map<UUID, AgentProgress> progress = prepareAgents(request.agentIds(), TestType.CLICKHOUSE);
        try {
            Map<UUID, Assignment> definitions = new LinkedHashMap<>();
            for (UUID agentId : request.agentIds()) {
                ClickHouseConnectionSpec spec = connections.get(agentId);
                ClickHouseTestRequest agentRequest = clickHouseRequestForEndpoint(request.testRequest(), spec.endpoint());
                definitions.put(agentId, new Assignment(runId, agentId, TestType.CLICKHOUSE, null,
                        agentRequest, spec, null, null, now));
            }
            DistributedRun run = new DistributedRun(runId, request.name(), TestType.CLICKHOUSE, null,
                    request.testRequest(), null, now, now, null, now, "RUNNING", null,
                    "Distributed test started", Map.copyOf(progress), Map.copyOf(definitions), firstEndpoint);
            persistAndActivate(run);
            return view(run);
        } catch (RuntimeException error) {
            request.agentIds().forEach(registry::release);
            throw error;
        }
    }

    public synchronized DistributedRunView createKafka(CreateDistributedKafkaTestRequest request) {
        ensureLoaded();
        requireAgents(request.agentIds());
        if (request.testRequest() == null) throw new IllegalArgumentException("Kafka producer request is required");
        KafkaProfileService.Profile profile = request.testRequest().profileId() == null
                ? kafkaProfiles.defaultProfile() : kafkaProfiles.get(request.testRequest().profileId());
        if (profile == null) throw new IllegalArgumentException("Kafka profile is required");
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<UUID, AgentProgress> progress = prepareAgents(request.agentIds(), TestType.KAFKA);
        try {
            Map<UUID, Assignment> definitions = new LinkedHashMap<>();
            for (UUID agentId : request.agentIds()) {
                definitions.put(agentId, new Assignment(runId, agentId, TestType.KAFKA, null,
                        null, null, request.testRequest(), profile, now));
            }
            DistributedRun run = new DistributedRun(runId, request.name(), TestType.KAFKA, null, null,
                    request.testRequest(), now, now, null, now, "RUNNING", null,
                    "Distributed test started", Map.copyOf(progress), Map.copyOf(definitions),
                    profile.bootstrapServers());
            persistAndActivate(run);
            return view(run);
        } catch (RuntimeException error) {
            request.agentIds().forEach(registry::release);
            throw error;
        }
    }

    private Map<UUID, AgentProgress> prepareAgents(List<UUID> agentIds, TestType type) {
        Map<UUID, AgentProgress> agents = new LinkedHashMap<>();
        List<UUID> reserved = new ArrayList<>();
        Instant now = Instant.now();
        String capability = type == TestType.CLICKHOUSE ? "CLICKHOUSE" : type == TestType.KAFKA ? "KAFKA" : "S3";
        try {
            for (UUID agentId : agentIds) {
                registry.reserve(agentId, capability);
                reserved.add(agentId);
                agents.put(agentId, new AgentProgress(agentId, "ASSIGNED", 0, 0, 0,
                        0, 0, 0, 0, 0, 0, null, now, null));
            }
            return agents;
        } catch (RuntimeException error) {
            reserved.forEach(registry::release);
            throw error;
        }
    }

    private void persistAndActivate(DistributedRun run) {
        persist(run);
        runs.put(run.id(), run);
        run.assignmentDefinitions().forEach(assignments::put);
    }

    public synchronized Assignment poll(UUID agentId, String token) {
        ensureLoaded();
        reconcileExpired(Instant.now());
        registry.authenticate(agentId, token);
        return assignments.get(agentId);
    }

    public synchronized DistributedRunView report(UUID agentId, String token, AgentStatistics stats) {
        ensureLoaded();
        validateAgentStatus(stats.status());
        AgentRegistry.AgentRecord agent = registry.authenticate(agentId, token);
        DistributedRun current = require(stats.runId());
        Assignment assignment = assignments.get(agentId);
        if (assignment == null || !assignment.runId().equals(stats.runId())) {
            AgentProgress persisted = current.agents().get(agentId);
            if (persisted != null && terminal(persisted.status()) && terminal(stats.status())) {
                return view(current);
            }
            throw new IllegalArgumentException("No matching assignment");
        }
        if (stats.latencyHistogram() != null) LatencyHistogramCodec.decode(stats.latencyHistogram());

        Instant now = Instant.now();
        AgentProgress reported = new AgentProgress(agentId, stats.status(), stats.completedOperations(),
                stats.rowsProcessed(), stats.bytesTransferred(), stats.operationsPerSecond(),
                stats.throughputMiBps(), stats.p50LatencyMs(), stats.p95LatencyMs(), stats.p99LatencyMs(),
                stats.errors(), stats.message(), now, stats.latencyHistogram());
        Map<UUID, AgentProgress> progress = new LinkedHashMap<>(current.agents());
        progress.put(agentId, reported);

        String status = current.status();
        Instant finishedAt = current.finishedAt();
        String stopReason = current.stopReason();
        String message = stats.message();
        if (progress.values().stream().allMatch(value -> terminal(value.status()))) {
            status = aggregateStatus(progress.values());
            finishedAt = now;
            stopReason = "FAILED".equals(status) ? "ERROR"
                    : "CANCELLED".equals(status) ? "AGENT_CANCELLED" : "NORMAL";
            message = "Distributed test " + status.toLowerCase(java.util.Locale.ROOT);
        }
        DistributedRun updated = new DistributedRun(current.id(), current.name(), current.testType(),
                current.s3Request(), current.clickHouseRequest(), current.kafkaRequest(), current.createdAt(),
                current.startedAt(), finishedAt, now, status, stopReason, message, Map.copyOf(progress),
                current.assignmentDefinitions(), current.primaryEndpoint());

        if (terminal(reported.status()) && updated.testType() == TestType.S3) {
            persistS3Agent(updated, agent, stats);
        }
        if (terminal(updated.status()) && updated.testType() == TestType.CLICKHOUSE) {
            persistClickHouse(updated);
        }
        persist(updated);
        if (terminal(reported.status())) registry.release(agentId);
        runs.put(updated.id(), updated);
        if (terminal(reported.status())) assignments.remove(agentId);
        return view(updated);
    }

    private void persistS3Agent(DistributedRun run, AgentRegistry.AgentRecord agent, AgentStatistics stats) {
        UUID historyId = UUID.nameUUIDFromBytes((run.id() + ":" + agent.id()).getBytes(StandardCharsets.UTF_8));
        testHistory.saveDistributed(new TestHistoryStore.DistributedHistoryResult(historyId, run.s3Request(),
                agent.name(), stats.status(), run.startedAt(), Instant.now(), stats.completedOperations(),
                stats.bytesTransferred(), stats.throughputMiBps(), stats.p50LatencyMs(), stats.p95LatencyMs(),
                stats.p99LatencyMs(), stats.errors(), stats.message()));
    }

    private void persistClickHouse(DistributedRun run) {
        DistributedRunView aggregate = view(run);
        clickHouseHistory.saveDistributed(run.id(), run.clickHouseRequest(), run.primaryEndpoint(), run.status(),
                run.startedAt(), run.finishedAt(), aggregate.rowsProcessed(), aggregate.bytesTransferred(),
                aggregate.completedOperations(), aggregate.errors(), aggregate.rowsPerSecond(),
                aggregate.throughputMiBps(), aggregate.operationsPerSecond(), aggregate.p50LatencyMs(),
                aggregate.p95LatencyMs(), aggregate.p99LatencyMs(),
                "Distributed ClickHouse test: " + run.agents().size() + " agents");
    }

    public List<DistributedRunView> list() {
        ensureLoaded();
        return runs.values().stream().sorted(Comparator.comparing(DistributedRun::createdAt).reversed())
                .map(this::view).toList();
    }

    public DistributedRunView get(UUID id) {
        ensureLoaded();
        return view(require(id));
    }

    private DistributedRun require(UUID id) {
        DistributedRun run = runs.get(id);
        if (run == null) throw new IllegalArgumentException("Distributed test not found");
        return run;
    }

    private DistributedRunView view(DistributedRun run) {
        List<AgentProgress> agents = new ArrayList<>(run.agents().values());
        long operations = agents.stream().mapToLong(AgentProgress::completedOperations).sum();
        long rows = agents.stream().mapToLong(AgentProgress::rowsProcessed).sum();
        long bytes = agents.stream().mapToLong(AgentProgress::bytesTransferred).sum();
        double operationsPerSecond = agents.stream().mapToDouble(AgentProgress::operationsPerSecond).sum();
        double throughput = agents.stream().mapToDouble(AgentProgress::throughputMiBps).sum();
        long errors = agents.stream().mapToLong(AgentProgress::errors).sum();
        LatencyAggregate latency = aggregateLatency(agents);
        double rowsPerSecond = run.testType() == TestType.CLICKHOUSE
                ? agents.stream().mapToDouble(agent -> agent.operationsPerSecond() > 0
                        && agent.completedOperations() > 0
                        ? agent.rowsProcessed() * agent.operationsPerSecond() / agent.completedOperations() : 0).sum()
                : 0;
        return new DistributedRunView(run.id(), run.name(), run.testType(), run.status(), run.startedAt(),
                run.finishedAt(), safeRequest(run.s3Request()), run.clickHouseRequest(), run.kafkaRequest(),
                operations, rows, bytes, operationsPerSecond, rowsPerSecond, throughput, latency.p50(),
                latency.p95(), latency.p99(), errors,
                agents.stream().sorted(Comparator.comparing(AgentProgress::agentId)).toList(),
                latencyScope(run), latency.method(), run.createdAt(), run.updatedAt(), run.stopReason(), run.message());
    }

    private static LatencyAggregate aggregateLatency(List<AgentProgress> agents) {
        List<AgentProgress> measured = agents.stream()
                .filter(agent -> agent.completedOperations() > 0).toList();
        if (measured.isEmpty()) return new LatencyAggregate(0, 0, 0, "NO_SAMPLES");
        if (measured.stream().anyMatch(agent -> agent.latencyHistogram() == null
                || agent.latencyHistogram().isBlank())) {
            return new LatencyAggregate(maxLatency(measured, 50), maxLatency(measured, 95),
                    maxLatency(measured, 99), "LEGACY_MAX");
        }
        Histogram merged = null;
        for (AgentProgress agent : measured) {
            Histogram next = LatencyHistogramCodec.decode(agent.latencyHistogram());
            if (next == null) continue;
            if (merged == null) merged = next.copy();
            else merged.add(next);
        }
        if (merged == null || merged.getTotalCount() == 0) {
            return new LatencyAggregate(0, 0, 0, "NO_SAMPLES");
        }
        return new LatencyAggregate(merged.getValueAtPercentile(50), merged.getValueAtPercentile(95),
                merged.getValueAtPercentile(99), "HDR_HISTOGRAM");
    }

    private static double maxLatency(List<AgentProgress> agents, int percentile) {
        return agents.stream().filter(agent -> agent.completedOperations() > 0)
                .mapToDouble(agent -> percentile == 50 ? agent.p50LatencyMs()
                        : percentile == 99 ? agent.p99LatencyMs() : agent.p95LatencyMs())
                .max().orElse(0);
    }

    private static String latencyScope(DistributedRun run) {
        if (run.testType() == TestType.CLICKHOUSE) return "CLICKHOUSE_QUERY";
        if (run.testType() == TestType.KAFKA) return "KAFKA_PRODUCE_ACK";
        return S3LatencyScope.forRequest(run.s3Request()).name();
    }

    @Scheduled(initialDelayString = "${s3perf.distributed.recovery-scan-initial-delay-ms:30000}",
            fixedDelayString = "${s3perf.distributed.recovery-scan-interval-ms:30000}")
    public synchronized void reconcileRecoveredRuns() {
        if (!persistenceConfigured()) return;
        ensureLoaded();
        reconcileExpired(Instant.now());
    }

    private void reconcileExpired(Instant now) {
        for (DistributedRun run : List.copyOf(runs.values())) {
            if (terminal(run.status()) || Duration.between(run.updatedAt(), now).compareTo(recoveryTimeout) <= 0) {
                continue;
            }
            List<UUID> pending = pendingAgentIds(run);
            DistributedRun cancelled = terminatePending(run, "CANCELLED", "RECOVERY_TIMEOUT",
                    "No agent progress was received within the recovery timeout", now);
            persist(cancelled);
            pending.forEach(registry::release);
            pending.forEach(assignments::remove);
            runs.put(cancelled.id(), cancelled);
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        if (!persistenceConfigured()) return;
        synchronized (this) {
            if (loaded) return;
            if (!persistenceConfigured()) return;
            for (DistributedRunRepository.PersistedRun persisted : repository.findAll()) {
                restore(persisted);
            }
            loaded = true;
        }
    }

    private void restore(DistributedRunRepository.PersistedRun persisted) {
        Map<UUID, AgentProgress> progress = new LinkedHashMap<>();
        persisted.agents().forEach(agent -> progress.put(agent.progress().agentId(), agent.progress()));
        Map<UUID, Assignment> definitions = new LinkedHashMap<>();
        Map<UUID, Assignment> activeAssignments = new LinkedHashMap<>();
        RuntimeException recoveryError = null;
        if (!terminal(persisted.status())) {
            try {
                for (DistributedRunRepository.PersistedAgent agent : persisted.agents()) {
                    if (terminal(agent.progress().status())) continue;
                    Assignment assignment = restoreAssignment(persisted.id(), agent.progress().agentId(),
                            agent.assignment());
                    definitions.put(agent.progress().agentId(), assignment);
                    activeAssignments.put(agent.progress().agentId(), assignment);
                }
            } catch (RuntimeException error) {
                recoveryError = error;
            }
        }
        DistributedRun run = new DistributedRun(persisted.id(), persisted.name(), persisted.testType(),
                persisted.s3Request(), persisted.clickHouseRequest(), persisted.kafkaRequest(),
                persisted.createdAt(), persisted.startedAt(), persisted.finishedAt(), persisted.updatedAt(),
                persisted.status(), persisted.stopReason(), persisted.message(), Map.copyOf(progress),
                Map.copyOf(definitions), persisted.primaryEndpoint());
        if (recoveryError != null) {
            Instant now = Instant.now();
            List<UUID> pending = pendingAgentIds(run);
            run = terminatePending(run, "FAILED", "RECOVERY_FAILED",
                    "Cannot restore assignment: " + rootMessage(recoveryError), now);
            persist(run);
            pending.forEach(registry::release);
        } else {
            activeAssignments.forEach(assignments::put);
        }
        runs.put(run.id(), run);
    }

    private Assignment restoreAssignment(UUID runId, UUID agentId,
                                         DistributedRunRepository.StoredAssignment stored) {
        if (stored == null || stored.testType() == null) {
            throw new IllegalStateException("Persisted assignment is missing");
        }
        if (stored.testType() == TestType.CLICKHOUSE) {
            if (stored.clickHouseRequest() == null) {
                throw new IllegalStateException("Persisted ClickHouse request is missing");
            }
            ClickHouseConnectionSpec connection = clickHouseProfiles.connectionSpec(
                    stored.clickHouseRequest().profileId(), stored.clickHouseEndpoint());
            return new Assignment(runId, agentId, TestType.CLICKHOUSE, null, stored.clickHouseRequest(),
                    connection, null, null, stored.assignedAt());
        }
        if (stored.testType() == TestType.KAFKA) {
            if (stored.kafkaRequest() == null || stored.kafkaProfile() == null) {
                throw new IllegalStateException("Persisted Kafka assignment is incomplete");
            }
            return new Assignment(runId, agentId, TestType.KAFKA, null, null, null,
                    stored.kafkaRequest(), stored.kafkaProfile(), stored.assignedAt());
        }
        if (stored.testRequest() == null) throw new IllegalStateException("Persisted S3 request is missing");
        return new Assignment(runId, agentId, TestType.S3, stored.testRequest(), null, null,
                null, null, stored.assignedAt());
    }

    private DistributedRun terminatePending(DistributedRun run, String status, String stopReason,
                                            String message, Instant now) {
        Map<UUID, AgentProgress> progress = new LinkedHashMap<>(run.agents());
        progress.replaceAll((agentId, current) -> terminal(current.status()) ? current
                : new AgentProgress(agentId, status, current.completedOperations(), current.rowsProcessed(),
                        current.bytesTransferred(), current.operationsPerSecond(), current.throughputMiBps(),
                        current.p50LatencyMs(), current.p95LatencyMs(), current.p99LatencyMs(),
                        current.errors(), message, now, current.latencyHistogram()));
        return new DistributedRun(run.id(), run.name(), run.testType(), run.s3Request(),
                run.clickHouseRequest(), run.kafkaRequest(), run.createdAt(), run.startedAt(), now, now,
                status, stopReason, message, Map.copyOf(progress), run.assignmentDefinitions(),
                run.primaryEndpoint());
    }

    private List<UUID> pendingAgentIds(DistributedRun run) {
        return run.agents().values().stream().filter(agent -> !terminal(agent.status()))
                .map(AgentProgress::agentId).toList();
    }

    private void persist(DistributedRun run) {
        if (repository != null) repository.save(run);
    }

    private boolean persistenceConfigured() {
        return repository == null || settingsService == null
                || settingsService.load().postgresql().configured();
    }

    private static ClickHouseTestRequest clickHouseRequestForEndpoint(ClickHouseTestRequest request,
                                                                      String endpoint) {
        return new ClickHouseTestRequest(request.profileId(), endpoint, request.table(), request.operation(),
                request.concurrency(), request.batchSize(), request.rowCount(), request.durationSeconds(),
                request.warmupSeconds(), request.payloadBytes(), request.queryCount(), request.autoCreateTable());
    }

    private static TestRequest safeRequest(TestRequest request) {
        if (request == null) return null;
        return new TestRequest(request.endpoint(), request.bucket(), request.region(), null, null,
                request.pathStyleAccess(), request.objectKey(), request.objectSizeMiB(), request.partSizeMiB(),
                request.parallelism(), request.objectCount(), request.deleteAfterTest(), request.operation(),
                request.executionMode(), request.durationSeconds(), request.warmupSeconds(),
                request.workloadProfile(), request.workloadWeights(), request.targetOperationsPerSecond(),
                request.operationThreads(), request.profileId());
    }

    private static String aggregateStatus(Iterable<AgentProgress> agents) {
        boolean failed = false;
        boolean cancelled = false;
        boolean completedWithErrors = false;
        for (AgentProgress agent : agents) {
            if ("FAILED".equals(agent.status())) failed = true;
            if ("CANCELLED".equals(agent.status())) cancelled = true;
            if ("COMPLETED_WITH_ERRORS".equals(agent.status())) completedWithErrors = true;
        }
        if (failed) return "FAILED";
        if (cancelled) return "CANCELLED";
        return completedWithErrors ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
    }

    private static void validateAgentStatus(String status) {
        if (status == null || !AGENT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported agent status: " + status);
        }
    }

    private static void requireAgents(List<UUID> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            throw new IllegalArgumentException("At least one agent is required");
        }
        if (agentIds.stream().distinct().count() != agentIds.size()) {
            throw new IllegalArgumentException("Agent IDs must be unique");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean terminal(String status) {
        return "COMPLETED".equals(status) || "COMPLETED_WITH_ERRORS".equals(status)
                || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record CreateDistributedTestRequest(String name, List<UUID> agentIds, TestRequest testRequest) { }

    public record CreateDistributedClickHouseTestRequest(String name, List<UUID> agentIds,
                                                          ClickHouseTestRequest testRequest,
                                                          Map<UUID, String> endpointByAgent) { }

    public record CreateDistributedKafkaTestRequest(String name, List<UUID> agentIds,
                                                     KafkaProducerRunService.ProducerRequest testRequest) { }

    public record Assignment(UUID runId, UUID agentId, TestType testType, TestRequest testRequest,
                             ClickHouseTestRequest clickHouseRequest,
                             ClickHouseConnectionSpec clickHouseConnection,
                             KafkaProducerRunService.ProducerRequest kafkaRequest,
                             KafkaProfileService.Profile kafkaProfile, Instant assignedAt) { }

    public record AgentStatistics(UUID runId, String status, long completedOperations, long rowsProcessed,
                                  long bytesTransferred, double operationsPerSecond, double throughputMiBps,
                                  double p50LatencyMs, double p95LatencyMs, double p99LatencyMs, long errors,
                                  String message, String latencyHistogram) { }

    public record AgentProgress(UUID agentId, String status, long completedOperations, long rowsProcessed,
                                long bytesTransferred, double operationsPerSecond, double throughputMiBps,
                                double p50LatencyMs, double p95LatencyMs, double p99LatencyMs, long errors,
                                String message, Instant updatedAt, @JsonIgnore String latencyHistogram) { }

    private record LatencyAggregate(double p50, double p95, double p99, String method) { }

    record DistributedRun(UUID id, String name, TestType testType, TestRequest s3Request,
                          ClickHouseTestRequest clickHouseRequest,
                          KafkaProducerRunService.ProducerRequest kafkaRequest, Instant createdAt,
                          Instant startedAt, Instant finishedAt, Instant updatedAt, String status,
                          String stopReason, String message, Map<UUID, AgentProgress> agents,
                          Map<UUID, Assignment> assignmentDefinitions, String primaryEndpoint) { }

    public record DistributedRunView(UUID id, String name, TestType testType, String status, Instant startedAt,
                                     Instant finishedAt, TestRequest testRequest,
                                     ClickHouseTestRequest clickHouseRequest,
                                     KafkaProducerRunService.ProducerRequest kafkaRequest,
                                     long completedOperations, long rowsProcessed, long bytesTransferred,
                                     double operationsPerSecond, double rowsPerSecond, double throughputMiBps,
                                     double p50LatencyMs, double p95LatencyMs, double p99LatencyMs, long errors,
                                     List<AgentProgress> agents, String latencyScope, String latencyAggregation,
                                     Instant createdAt, Instant updatedAt, String stopReason, String message) { }
}
