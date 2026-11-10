package dev.phibus.s3.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.clickhouse.ClickHouseConnectionSpec;
import dev.phibus.s3.clickhouse.ClickHouseHistoryStore;
import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.kafka.KafkaProfileService;
import dev.phibus.s3.test.TestRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DistributedRunPersistenceIT {
    private static final String REGISTRATION_TOKEN = "registration-secret";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("s3perf")
            .withUsername("s3perf")
            .withPassword("s3perf-test");

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private AgentRegistryRepository agentRepository;
    private DistributedRunRepository runRepository;

    @BeforeEach
    void prepareDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM distributed_test_agent");
        jdbc.update("DELETE FROM distributed_test");
        jdbc.update("DELETE FROM agent");
        objectMapper = new ObjectMapper().findAndRegisterModules();
        agentRepository = new AgentRegistryRepository(jdbc, objectMapper);
        runRepository = new DistributedRunRepository(jdbc, objectMapper);
    }

    @Test
    void assignmentAndProgressSurviveCoordinatorRestarts() {
        AgentRegistry firstRegistry = registry();
        AgentRegistry.RegistrationResult registration = register(firstRegistry, "S3");
        DistributedTestService firstCoordinator = service(firstRegistry, mock(ClickHouseProfileService.class),
                Duration.ofMinutes(5));
        TestRequest request = s3Request();

        DistributedTestService.DistributedRunView created = firstCoordinator.create(
                new DistributedTestService.CreateDistributedTestRequest(
                        "durable-run", List.of(registration.agentId()), request));
        DistributedTestService.DistributedRunView queued = firstCoordinator.report(
                registration.agentId(), registration.agentToken(),
                new DistributedTestService.AgentStatistics(created.id(), "QUEUED", 0, 0,
                        0, 0, 0, 0, 0, 0, 0, "waiting for capacity", null));
        assertThat(queued.agents().getFirst().status()).isEqualTo("QUEUED");
        firstCoordinator.report(registration.agentId(), registration.agentToken(),
                new DistributedTestService.AgentStatistics(created.id(), "RUNNING", 1, 0,
                        5 * 1024 * 1024, 1, 5, 10, 20, 30, 0, "in progress", null));

        AgentRegistry restartedRegistry = registry();
        DistributedTestService restartedCoordinator = service(restartedRegistry,
                mock(ClickHouseProfileService.class), Duration.ofMinutes(5));
        DistributedTestService.Assignment restored = restartedCoordinator.poll(
                registration.agentId(), registration.agentToken());

        assertThat(restored).isNotNull();
        assertThat(restored.runId()).isEqualTo(created.id());
        assertThat(restored.testRequest().bucket()).isEqualTo("bucket");
        DistributedTestService.DistributedRunView restoredRun = restartedCoordinator.get(created.id());
        assertThat(restoredRun.status()).isEqualTo("RUNNING");
        assertThat(restoredRun.completedOperations()).isEqualTo(1);
        assertThat(restoredRun.agents().getFirst().message()).isEqualTo("in progress");

        DistributedTestService.DistributedRunView completed = restartedCoordinator.report(
                registration.agentId(), registration.agentToken(),
                new DistributedTestService.AgentStatistics(created.id(), "COMPLETED", 2, 0,
                        10 * 1024 * 1024, 2, 5, 10, 20, 30, 0, "done", null));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.stopReason()).isEqualTo("NORMAL");

        DistributedTestService.DistributedRunView retried = restartedCoordinator.report(
                registration.agentId(), registration.agentToken(),
                new DistributedTestService.AgentStatistics(created.id(), "COMPLETED", 2, 0,
                        10 * 1024 * 1024, 2, 5, 10, 20, 30, 0, "done", null));
        assertThat(retried.status()).isEqualTo("COMPLETED");

        DistributedTestService secondRestart = service(registry(), mock(ClickHouseProfileService.class),
                Duration.ofMinutes(5));
        assertThat(secondRestart.get(created.id()).status()).isEqualTo("COMPLETED");
        assertThat(secondRestart.poll(registration.agentId(), registration.agentToken())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM distributed_test WHERE id=?", String.class, created.id()))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM distributed_test_agent
                 WHERE distributed_test_id=? AND agent_id=?
                """, String.class, created.id(), registration.agentId())).isEqualTo("COMPLETED");
    }

    @Test
    void recoveryTimeoutCancelsStaleRunAndReleasesAgent() {
        AgentRegistry firstRegistry = registry();
        AgentRegistry.RegistrationResult registration = register(firstRegistry, "S3");
        DistributedTestService firstCoordinator = service(firstRegistry, mock(ClickHouseProfileService.class),
                Duration.ofMinutes(5));
        DistributedTestService.DistributedRunView created = firstCoordinator.create(
                new DistributedTestService.CreateDistributedTestRequest(
                        "stale-run", List.of(registration.agentId()), s3Request()));
        jdbc.update("UPDATE distributed_test SET updated_at=CURRENT_TIMESTAMP - INTERVAL '10 seconds' WHERE id=?",
                created.id());

        AgentRegistry restartedRegistry = registry();
        DistributedTestService restartedCoordinator = service(restartedRegistry,
                mock(ClickHouseProfileService.class), Duration.ofSeconds(1));
        restartedCoordinator.reconcileRecoveredRuns();

        DistributedTestService.DistributedRunView cancelled = restartedCoordinator.get(created.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.stopReason()).isEqualTo("RECOVERY_TIMEOUT");
        assertThat(restartedCoordinator.poll(registration.agentId(), registration.agentToken())).isNull();
        assertThat(restartedRegistry.list().getFirst().status()).isEqualTo(AgentRegistry.AgentStatus.ONLINE);
    }

    @Test
    void decryptedClickHousePasswordIsNeverPersistedInRunState() {
        AgentRegistry registry = registry();
        AgentRegistry.RegistrationResult registration = register(registry, "CLICKHOUSE");
        UUID profileId = UUID.randomUUID();
        ClickHouseProfileService profiles = mock(ClickHouseProfileService.class);
        when(profiles.connectionSpec(profileId, "http://clickhouse:8123"))
                .thenReturn(new ClickHouseConnectionSpec("http://clickhouse:8123", "default", "load-user",
                        "decrypted-secret", 5000, 30));
        ClickHouseTestRequest request = new ClickHouseTestRequest(profileId, "http://clickhouse:8123",
                "load_table", "INSERT", 2, 1000, 10_000, 0, 0, 128, true);

        service(registry, profiles, Duration.ofMinutes(5)).createClickHouse(
                new DistributedTestService.CreateDistributedClickHouseTestRequest(
                        "clickhouse-run", List.of(registration.agentId()), request, Map.of()));

        String assignmentJson = jdbc.queryForObject(
                "SELECT assignment_json FROM distributed_test_agent", String.class);
        String requestJson = jdbc.queryForObject("SELECT request_json FROM distributed_test", String.class);
        assertThat(assignmentJson).doesNotContain("decrypted-secret").doesNotContain("password");
        assertThat(requestJson).doesNotContain("decrypted-secret").doesNotContain("password");
    }

    private AgentRegistry registry() {
        return new AgentRegistry(agentRepository, REGISTRATION_TOKEN);
    }

    private AgentRegistry.RegistrationResult register(AgentRegistry registry, String capability) {
        return registry.register(new AgentRegistry.RegistrationRequest("agent-1", "host-1",
                "http://agent-1:8080", "2.5.0-SNAPSHOT", 4, 2048,
                Map.of("capabilities", capability, "runtime.activeRuns", "0")), REGISTRATION_TOKEN);
    }

    private DistributedTestService service(AgentRegistry registry, ClickHouseProfileService clickHouseProfiles,
                                           Duration recoveryTimeout) {
        return new DistributedTestService(registry, clickHouseProfiles, mock(ClickHouseHistoryStore.class),
                mock(TestHistoryStore.class), mock(KafkaProfileService.class), runRepository, recoveryTimeout);
    }

    private static TestRequest s3Request() {
        return new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "object.bin", 10, 5, 2, 1, false, "UPLOAD");
    }
}
