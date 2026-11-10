package dev.phibus.s3.distributed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.kafka.KafkaProfileService;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class DistributedRunRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    DistributedRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    void save(DistributedTestService.DistributedRun run) {
        Aggregates totals = aggregates(run.agents().values());
        jdbc.update("""
                INSERT INTO distributed_test(id, status, requested_agents, started_at, finished_at, created_at,
                    aggregate_bytes, aggregate_operations, aggregate_errors, name, test_type, request_json,
                    primary_endpoint, stop_reason, message, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status,
                    requested_agents=EXCLUDED.requested_agents, started_at=EXCLUDED.started_at,
                    finished_at=EXCLUDED.finished_at, aggregate_bytes=EXCLUDED.aggregate_bytes,
                    aggregate_operations=EXCLUDED.aggregate_operations,
                    aggregate_errors=EXCLUDED.aggregate_errors, name=EXCLUDED.name,
                    test_type=EXCLUDED.test_type, request_json=EXCLUDED.request_json,
                    primary_endpoint=EXCLUDED.primary_endpoint, stop_reason=EXCLUDED.stop_reason,
                    message=EXCLUDED.message, updated_at=EXCLUDED.updated_at
                """, run.id(), run.status(), run.agents().size(), timestamp(run.startedAt()),
                timestamp(run.finishedAt()), timestamp(run.createdAt()), totals.bytes(), totals.operations(),
                totals.errors(), run.name(), run.testType().name(), write(new RequestEnvelope(
                        safe(run.s3Request()), run.clickHouseRequest(), run.kafkaRequest())),
                run.primaryEndpoint(), run.stopReason(), run.message(), timestamp(run.updatedAt()));

        for (DistributedTestService.AgentProgress progress : run.agents().values()) {
            DistributedTestService.Assignment assignment = run.assignmentDefinitions().get(progress.agentId());
            StoredAssignment storedAssignment = assignment == null ? null : StoredAssignment.from(assignment);
            String assignmentJson = storedAssignment == null ? "{}" : write(storedAssignment);
            jdbc.update("""
                    INSERT INTO distributed_test_agent(distributed_test_id, agent_id, status, started_at,
                        finished_at, bytes_transferred, operations, errors, assignment_json, rows_processed,
                        operations_per_second, throughput_mibps, p50_latency_ms, p95_latency_ms,
                        p99_latency_ms, message, latency_histogram, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (distributed_test_id, agent_id) DO UPDATE SET status=EXCLUDED.status,
                        started_at=COALESCE(distributed_test_agent.started_at, EXCLUDED.started_at),
                        finished_at=EXCLUDED.finished_at, bytes_transferred=EXCLUDED.bytes_transferred,
                        operations=EXCLUDED.operations, errors=EXCLUDED.errors,
                        assignment_json=COALESCE(NULLIF(EXCLUDED.assignment_json, '{}'),
                            distributed_test_agent.assignment_json), rows_processed=EXCLUDED.rows_processed,
                        operations_per_second=EXCLUDED.operations_per_second,
                        throughput_mibps=EXCLUDED.throughput_mibps,
                        p50_latency_ms=EXCLUDED.p50_latency_ms,
                        p95_latency_ms=EXCLUDED.p95_latency_ms,
                        p99_latency_ms=EXCLUDED.p99_latency_ms, message=EXCLUDED.message,
                        latency_histogram=EXCLUDED.latency_histogram, updated_at=EXCLUDED.updated_at
                    """, run.id(), progress.agentId(), progress.status(), timestamp(run.startedAt()),
                    terminal(progress.status()) ? timestamp(progress.updatedAt()) : null,
                    progress.bytesTransferred(), progress.completedOperations(), progress.errors(),
                    assignmentJson, progress.rowsProcessed(), progress.operationsPerSecond(),
                    progress.throughputMiBps(), progress.p50LatencyMs(), progress.p95LatencyMs(),
                    progress.p99LatencyMs(), progress.message(), progress.latencyHistogram(),
                    timestamp(progress.updatedAt()));
        }
    }

    List<PersistedRun> findAll() {
        Map<UUID, List<PersistedAgent>> agents = new LinkedHashMap<>();
        jdbc.query("""
                SELECT distributed_test_id, agent_id, status, bytes_transferred, operations, errors,
                       assignment_json, rows_processed, operations_per_second, throughput_mibps,
                       p50_latency_ms, p95_latency_ms, p99_latency_ms, message, latency_histogram, updated_at
                  FROM distributed_test_agent
                 ORDER BY distributed_test_id, agent_id
                """, result -> {
                    UUID runId = result.getObject("distributed_test_id", UUID.class);
                    agents.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(mapAgent(result));
                });
        return jdbc.query("""
                SELECT id, name, test_type, status, created_at, started_at, finished_at, updated_at,
                       request_json, primary_endpoint, stop_reason, message
                  FROM distributed_test
                 ORDER BY created_at
                """, (result, row) -> mapRun(result, agents.getOrDefault(
                        result.getObject("id", UUID.class), List.of())));
    }

    private PersistedRun mapRun(ResultSet result, List<PersistedAgent> agents) throws SQLException {
        RequestEnvelope request = read(result.getString("request_json"), RequestEnvelope.class,
                new RequestEnvelope(null, null, null));
        return new PersistedRun(result.getObject("id", UUID.class), result.getString("name"),
                TestType.valueOf(result.getString("test_type")), result.getString("status"),
                instant(result.getTimestamp("created_at")), instant(result.getTimestamp("started_at")),
                instant(result.getTimestamp("finished_at")), instant(result.getTimestamp("updated_at")),
                request.s3Request(), request.clickHouseRequest(), request.kafkaRequest(),
                result.getString("primary_endpoint"), result.getString("stop_reason"),
                result.getString("message"), List.copyOf(agents));
    }

    private PersistedAgent mapAgent(ResultSet result) throws SQLException {
        StoredAssignment assignment = read(result.getString("assignment_json"), StoredAssignment.class, null);
        DistributedTestService.AgentProgress progress = new DistributedTestService.AgentProgress(
                result.getObject("agent_id", UUID.class), result.getString("status"),
                result.getLong("operations"), result.getLong("rows_processed"),
                result.getLong("bytes_transferred"), result.getDouble("operations_per_second"),
                result.getDouble("throughput_mibps"), result.getDouble("p50_latency_ms"),
                result.getDouble("p95_latency_ms"), result.getDouble("p99_latency_ms"),
                result.getLong("errors"), result.getString("message"),
                instant(result.getTimestamp("updated_at")), result.getString("latency_histogram"));
        return new PersistedAgent(progress, assignment);
    }

    private String write(Object value) {
        if (value == null) return "{}";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot persist distributed run state", error);
        }
    }

    private <T> T read(String json, Class<T> type, T fallback) throws SQLException {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) return fallback;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new SQLException("Cannot read persisted distributed run state", error);
        }
    }

    private static TestRequest safe(TestRequest request) {
        if (request == null) return null;
        return new TestRequest(request.endpoint(), request.bucket(), request.region(), null, null,
                request.pathStyleAccess(), request.objectKey(), request.objectSizeMiB(), request.partSizeMiB(),
                request.parallelism(), request.objectCount(), request.deleteAfterTest(), request.operation(),
                request.executionMode(), request.durationSeconds(), request.warmupSeconds(),
                request.workloadProfile(), request.workloadWeights(), request.targetOperationsPerSecond(),
                request.operationThreads(), request.profileId());
    }

    private static Aggregates aggregates(Iterable<DistributedTestService.AgentProgress> agents) {
        long bytes = 0;
        long operations = 0;
        long errors = 0;
        for (DistributedTestService.AgentProgress agent : agents) {
            bytes += agent.bytesTransferred();
            operations += agent.completedOperations();
            errors += agent.errors();
        }
        return new Aggregates(bytes, operations, errors);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static boolean terminal(String status) {
        return "COMPLETED".equals(status) || "COMPLETED_WITH_ERRORS".equals(status)
                || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    record RequestEnvelope(TestRequest s3Request, ClickHouseTestRequest clickHouseRequest,
                           KafkaProducerRunService.ProducerRequest kafkaRequest) { }

    record StoredAssignment(TestType testType, TestRequest testRequest,
                            ClickHouseTestRequest clickHouseRequest, String clickHouseEndpoint,
                            KafkaProducerRunService.ProducerRequest kafkaRequest,
                            KafkaProfileService.Profile kafkaProfile, Instant assignedAt) {
        static StoredAssignment from(DistributedTestService.Assignment assignment) {
            String endpoint = assignment.clickHouseConnection() == null
                    ? null : assignment.clickHouseConnection().endpoint();
            return new StoredAssignment(assignment.testType(), safe(assignment.testRequest()),
                    assignment.clickHouseRequest(), endpoint, assignment.kafkaRequest(),
                    assignment.kafkaProfile(), assignment.assignedAt());
        }
    }

    record PersistedAgent(DistributedTestService.AgentProgress progress, StoredAssignment assignment) { }

    record PersistedRun(UUID id, String name, TestType testType, String status, Instant createdAt,
                        Instant startedAt, Instant finishedAt, Instant updatedAt, TestRequest s3Request,
                        ClickHouseTestRequest clickHouseRequest,
                        KafkaProducerRunService.ProducerRequest kafkaRequest, String primaryEndpoint,
                        String stopReason, String message, List<PersistedAgent> agents) { }

    private record Aggregates(long bytes, long operations, long errors) { }
}
