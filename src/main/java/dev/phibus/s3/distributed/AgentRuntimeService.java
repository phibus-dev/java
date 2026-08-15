package dev.phibus.s3.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.clickhouse.ClickHouseTestRun;
import dev.phibus.s3.clickhouse.ClickHouseTestRunService;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import dev.phibus.s3.test.TestStatus;
import dev.phibus.s3.test.TestType;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "AGENT")
public class AgentRuntimeService {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRuntimeService.class);
    private static final Map<String, String> AGENT_TAGS = Map.of("mode", "AGENT", "capabilities", "S3,CLICKHOUSE");

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final TestRunService testRunService;
    private final ClickHouseTestRunService clickHouseTestRunService;
    private final Executor executor;
    private final String coordinatorUrl;
    private final String registrationToken;
    private final String agentName;
    private final String advertisedAddress;
    private final Path identityFile;
    private final AtomicReference<AgentIdentity> identity = new AtomicReference<>();
    private final AtomicReference<ActiveAssignment> active = new AtomicReference<>();
    private final AtomicBoolean registering = new AtomicBoolean();

    public AgentRuntimeService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
            TestRunService testRunService, ClickHouseTestRunService clickHouseTestRunService,
            @Qualifier("testExecutor") Executor executor,
            @Value("${s3perf.agent.coordinator-url:http://localhost:8080}") String coordinatorUrl,
            @Value("${s3perf.agent.registration-token:change-me}") String registrationToken,
            @Value("${s3perf.agent.name:}") String configuredName,
            @Value("${s3perf.agent.address:}") String advertisedAddress,
            @Value("${s3perf.agent.identity-file:config/agent-identity.json}") String identityFile) {
        this.client = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.testRunService = testRunService;
        this.clickHouseTestRunService = clickHouseTestRunService;
        this.executor = executor;
        this.coordinatorUrl = stripTrailingSlash(coordinatorUrl);
        this.registrationToken = registrationToken;
        this.agentName = configuredName == null || configuredName.isBlank() ? hostname() : configuredName;
        this.advertisedAddress = advertisedAddress == null ? "" : advertisedAddress;
        this.identityFile = Path.of(identityFile).toAbsolutePath().normalize();
        this.identity.set(loadIdentity());
    }

    @Scheduled(fixedDelayString = "${s3perf.agent.heartbeat-interval-ms:15000}", initialDelay = 1000)
    public void heartbeat() {
        AgentIdentity current = ensureRegistered();
        if (current == null) return;
        try {
            client.post().uri(coordinatorUrl + "/api/agents/" + current.agentId() + "/heartbeat")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + current.agentToken())
                    .body(new AgentRegistry.HeartbeatRequest(version(), availableProcessors(), maxMemory(), AGENT_TAGS))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.NotFound e) {
            LOG.warn("Agent identity was rejected by coordinator; registration will be repeated");
            clearIdentity();
        } catch (RuntimeException e) {
            LOG.warn("Cannot send agent heartbeat: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${s3perf.agent.poll-interval-ms:2000}", initialDelay = 2000)
    public void pollAssignment() {
        AgentIdentity current = ensureRegistered();
        if (current == null || active.get() != null) return;
        try {
            DistributedTestService.Assignment assignment = client.get()
                    .uri(coordinatorUrl + "/api/distributed-tests/agent/" + current.agentId() + "/assignment")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + current.agentToken())
                    .retrieve().body(DistributedTestService.Assignment.class);
            if (assignment != null && active.compareAndSet(null, new ActiveAssignment(assignment, null)))
                executor.execute(() -> execute(assignment));
        } catch (HttpClientErrorException.NotFound ignored) {
            // No assignment is currently available.
        } catch (RuntimeException e) {
            LOG.warn("Cannot poll distributed assignment: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${s3perf.agent.statistics-interval-ms:2000}", initialDelay = 3000)
    public void reportProgress() {
        ActiveAssignment current = active.get();
        AgentIdentity agent = identity.get();
        if (current == null || current.localRunId() == null || agent == null) return;
        try {
            if (current.assignment().testType() == TestType.CLICKHOUSE) reportClickHouse(agent, current);
            else reportS3(agent, current);
        } catch (RuntimeException e) {
            LOG.warn("Cannot publish distributed test statistics: {}", e.getMessage());
        }
    }

    private void reportS3(AgentIdentity agent, ActiveAssignment current) {
        TestRun.Snapshot snapshot = testRunService.get(current.localRunId()).snapshot();
        DistributedTestService.AgentStatistics statistics = new DistributedTestService.AgentStatistics(
                current.assignment().runId(), status(snapshot.status()), snapshot.completedParts(), 0,
                snapshot.bytesTransferred(), snapshot.operationsPerSecond(), snapshot.averageSpeedMiBps(),
                snapshot.p50LatencyMs(), snapshot.p95LatencyMs(), snapshot.p99LatencyMs(),
                snapshot.failedParts(), snapshot.message());
        report(agent, statistics);
        if (terminal(snapshot.status())) active.compareAndSet(current, null);
    }

    private void reportClickHouse(AgentIdentity agent, ActiveAssignment current) {
        ClickHouseTestRun.Snapshot snapshot = clickHouseTestRunService.get(current.localRunId()).snapshot();
        DistributedTestService.AgentStatistics statistics = new DistributedTestService.AgentStatistics(
                current.assignment().runId(), snapshot.status().name(), snapshot.queries(), snapshot.rows(), snapshot.bytes(),
                snapshot.queriesPerSecond(), snapshot.mibPerSecond(), snapshot.p50LatencyMs(), snapshot.p95LatencyMs(),
                snapshot.p99LatencyMs(), snapshot.errors(), snapshot.message());
        report(agent, statistics);
        if (terminal(snapshot.status())) active.compareAndSet(current, null);
    }

    private void execute(DistributedTestService.Assignment assignment) {
        AgentIdentity agent = ensureRegistered();
        if (agent == null) { active.set(null); return; }
        try {
            UUID localRunId;
            if (assignment.testType() == TestType.CLICKHOUSE) {
                ClickHouseTestRun run = clickHouseTestRunService.createDistributed(
                        assignment.clickHouseRequest(), assignment.clickHouseConnection());
                localRunId = run.id();
            } else {
                TestRun run = testRunService.create(assignment.testRequest());
                localRunId = run.id();
            }
            active.set(new ActiveAssignment(assignment, localRunId));
        } catch (RuntimeException e) {
            LOG.error("Distributed assignment {} failed to start", assignment.runId(), e);
            reportFailure(agent, assignment.runId(), e.getMessage());
            active.set(null);
        }
    }

    private AgentIdentity ensureRegistered() {
        AgentIdentity existing = identity.get();
        if (existing != null || !registering.compareAndSet(false, true)) return existing;
        try {
            AgentRegistry.RegistrationRequest request = new AgentRegistry.RegistrationRequest(
                    agentName, hostname(), advertisedAddress, version(), availableProcessors(), maxMemory(), AGENT_TAGS);
            AgentRegistry.RegistrationResult result = client.post().uri(coordinatorUrl + "/api/agents/register")
                    .header("X-Agent-Registration-Token", registrationToken).body(request)
                    .retrieve().body(AgentRegistry.RegistrationResult.class);
            if (result == null) return null;
            AgentIdentity created = new AgentIdentity(result.agentId(), result.agentToken(), coordinatorUrl, Instant.now());
            saveIdentity(created);
            identity.set(created);
            LOG.info("Agent registered as {} with capabilities {}", created.agentId(), AGENT_TAGS.get("capabilities"));
            return created;
        } catch (RuntimeException e) {
            LOG.warn("Cannot register agent: {}", e.getMessage());
            return null;
        } finally {
            registering.set(false);
        }
    }

    private void report(AgentIdentity agent, DistributedTestService.AgentStatistics statistics) {
        client.post().uri(coordinatorUrl + "/api/distributed-tests/agent/" + agent.agentId() + "/statistics")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.agentToken())
                .body(statistics).retrieve().toBodilessEntity();
    }

    private void reportFailure(AgentIdentity agent, UUID runId, String message) {
        try {
            report(agent, new DistributedTestService.AgentStatistics(
                    runId, "FAILED", 0, 0, 0, 0, 0, 0, 0, 0, 1, message));
        } catch (RuntimeException e) {
            LOG.warn("Cannot report failed assignment: {}", e.getMessage());
        }
    }

    private AgentIdentity loadIdentity() {
        if (!Files.exists(identityFile)) return null;
        try {
            AgentIdentity loaded = objectMapper.readValue(identityFile.toFile(), AgentIdentity.class);
            return coordinatorUrl.equals(loaded.coordinatorUrl()) ? loaded : null;
        } catch (IOException e) {
            LOG.warn("Cannot read agent identity file: {}", e.getMessage());
            return null;
        }
    }

    private void saveIdentity(AgentIdentity value) {
        try {
            Files.createDirectories(identityFile.getParent());
            Path temporary = identityFile.resolveSibling(identityFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            Files.move(temporary, identityFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist agent identity", e);
        }
    }

    private void clearIdentity() {
        identity.set(null);
        try { Files.deleteIfExists(identityFile); }
        catch (IOException e) { LOG.warn("Cannot delete rejected agent identity: {}", e.getMessage()); }
    }

    private static boolean terminal(TestStatus status) {
        return status == TestStatus.COMPLETED || status == TestStatus.FAILED || status == TestStatus.CANCELLED;
    }
    private static boolean terminal(ClickHouseTestRun.Status status) {
        return status == ClickHouseTestRun.Status.COMPLETED || status == ClickHouseTestRun.Status.FAILED
                || status == ClickHouseTestRun.Status.CANCELLED;
    }
    private static String status(TestStatus status) {
        return switch (status) {
            case QUEUED -> "ASSIGNED";
            case RUNNING -> "RUNNING";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
    }
    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown-agent"; }
    }
    private static int availableProcessors() { return Runtime.getRuntime().availableProcessors(); }
    private static long maxMemory() { return Runtime.getRuntime().maxMemory(); }
    private static String version() {
        String value = AgentRuntimeService.class.getPackage().getImplementationVersion();
        return value == null ? "development" : value;
    }
    private static String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record AgentIdentity(UUID agentId, String agentToken, String coordinatorUrl, Instant registeredAt) { }
    private record ActiveAssignment(DistributedTestService.Assignment assignment, UUID localRunId) { }
}
