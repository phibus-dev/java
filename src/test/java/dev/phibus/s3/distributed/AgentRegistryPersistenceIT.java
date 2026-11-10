package dev.phibus.s3.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class AgentRegistryPersistenceIT {
    private static final String REGISTRATION_TOKEN = "registration-secret";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("s3perf")
            .withUsername("s3perf")
            .withPassword("s3perf-test");

    private JdbcTemplate jdbc;
    private AgentRegistryRepository repository;

    @BeforeEach
    void prepareDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM agent");
        repository = new AgentRegistryRepository(jdbc, new ObjectMapper());
    }

    @Test
    void identityTokenAndManagementSurviveCoordinatorRestart() {
        AgentRegistry firstCoordinator = new AgentRegistry(repository, REGISTRATION_TOKEN);
        AgentRegistry.RegistrationRequest request = new AgentRegistry.RegistrationRequest(
                "agent-1", "host-1", "http://agent-1:8080", "2.5.0-SNAPSHOT", 8, 4096,
                Map.of("capabilities", "S3,KAFKA,CLICKHOUSE"));
        AgentRegistry.RegistrationResult registration = firstCoordinator.register(request, REGISTRATION_TOKEN);
        firstCoordinator.requestUpdate(registration.agentId(), "2.5.0");
        firstCoordinator.setEnabled(registration.agentId(), false);

        AgentRegistry restartedCoordinator = new AgentRegistry(repository, REGISTRATION_TOKEN);
        AgentRegistry.AgentView restored = restartedCoordinator.list().getFirst();
        assertThat(restored.id()).isEqualTo(registration.agentId());
        assertThat(restored.enabled()).isFalse();
        assertThat(restored.status()).isEqualTo(AgentRegistry.AgentStatus.DISABLED);
        assertThat(restored.desiredVersion()).isEqualTo("2.5.0");
        assertThat(restored.updateRequested()).isTrue();

        restartedCoordinator.setEnabled(registration.agentId(), true);
        assertThat(restartedCoordinator.authenticate(registration.agentId(), registration.agentToken()).id())
                .isEqualTo(registration.agentId());
        restartedCoordinator.heartbeat(registration.agentId(), registration.agentToken(),
                new AgentRegistry.HeartbeatRequest("2.5.0", 8, 4096,
                        Map.of("capabilities", "S3,KAFKA,CLICKHOUSE")));

        AgentRegistry completedUpdateCoordinator = new AgentRegistry(repository, REGISTRATION_TOKEN);
        AgentRegistry.AgentView completed = completedUpdateCoordinator.list().getFirst();
        assertThat(completed.updateRequested()).isFalse();
        assertThat(completed.updateCompletedAt()).isNotNull();

        AgentRegistry.RegistrationResult rotated = completedUpdateCoordinator.register(request, REGISTRATION_TOKEN);
        assertThat(rotated.agentId()).isEqualTo(registration.agentId());
        assertThat(rotated.agentToken()).isNotEqualTo(registration.agentToken());

        AgentRegistry secondRestart = new AgentRegistry(repository, REGISTRATION_TOKEN);
        assertThat(secondRestart.authenticate(rotated.agentId(), rotated.agentToken()).id())
                .isEqualTo(registration.agentId());
        assertThatThrownBy(() -> secondRestart.authenticate(registration.agentId(), registration.agentToken()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void busyReservationIsReleasedWhenCoordinatorRestarts() {
        AgentRegistry firstCoordinator = new AgentRegistry(repository, REGISTRATION_TOKEN);
        AgentRegistry.RegistrationResult registration = firstCoordinator.register(
                new AgentRegistry.RegistrationRequest("agent-1", "host-1", "http://agent-1:8080",
                        "2.5.0-SNAPSHOT", 4, 2048, Map.of("capabilities", "S3")), REGISTRATION_TOKEN);
        firstCoordinator.reserve(registration.agentId(), "S3");

        AgentRegistry restartedCoordinator = new AgentRegistry(repository, REGISTRATION_TOKEN);
        assertThat(restartedCoordinator.list().getFirst().status()).isEqualTo(AgentRegistry.AgentStatus.BUSY);
        restartedCoordinator.heartbeat(registration.agentId(), registration.agentToken(),
                new AgentRegistry.HeartbeatRequest("2.5.0-SNAPSHOT", 4, 2048,
                        Map.of("capabilities", "S3", "runtime.activeRuns", "1")));
        assertThat(restartedCoordinator.list().getFirst().status()).isEqualTo(AgentRegistry.AgentStatus.BUSY);
        restartedCoordinator.heartbeat(registration.agentId(), registration.agentToken(),
                new AgentRegistry.HeartbeatRequest("2.5.0-SNAPSHOT", 4, 2048,
                        Map.of("capabilities", "S3", "runtime.activeRuns", "0")));
        assertThat(restartedCoordinator.list().getFirst().status()).isEqualTo(AgentRegistry.AgentStatus.ONLINE);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent WHERE id=?", String.class, registration.agentId())).isEqualTo("ONLINE");
        restartedCoordinator.reserve(registration.agentId(), "S3");
    }

    @Test
    void tokenIsPersistedOnlyAsHash() {
        AgentRegistry registry = new AgentRegistry(repository, REGISTRATION_TOKEN);
        AgentRegistry.RegistrationResult registration = registry.register(
                new AgentRegistry.RegistrationRequest("agent-1", "host-1", "http://agent-1:8080",
                        "2.5.0-SNAPSHOT", 4, 2048, Map.of()), REGISTRATION_TOKEN);

        String storedHash = jdbc.queryForObject(
                "SELECT agent_token_hash FROM agent WHERE id=?", String.class, registration.agentId());
        assertThat(storedHash).hasSize(64).doesNotContain(registration.agentToken());
    }

    @Test
    void legacyAgentWithoutTokenHashKeepsItsIdentityWhenItRegistersAgain() {
        UUID legacyId = UUID.randomUUID();
        Instant registeredAt = Instant.now().minusSeconds(3600);
        jdbc.update("""
                INSERT INTO agent(id, name, hostname, address, version, cpu_count, memory_bytes, status,
                                  tags_json, registered_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, legacyId, "agent-1", "host-1", "http://agent-1:8080", "2.4.0", 4, 2048,
                "ONLINE", "{}", Timestamp.from(registeredAt), Timestamp.from(registeredAt));

        AgentRegistry restartedCoordinator = new AgentRegistry(repository, REGISTRATION_TOKEN);
        AgentRegistry.RegistrationResult registration = restartedCoordinator.register(
                new AgentRegistry.RegistrationRequest("agent-1", "host-1", "http://agent-1:8080",
                        "2.5.0-SNAPSHOT", 8, 4096, Map.of()), REGISTRATION_TOKEN);

        assertThat(registration.agentId()).isEqualTo(legacyId);
        assertThat(registration.registeredAt()).isCloseTo(registeredAt, within(1, ChronoUnit.SECONDS));
        assertThat(restartedCoordinator.authenticate(legacyId, registration.agentToken()).id()).isEqualTo(legacyId);
    }
}
