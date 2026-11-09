package dev.phibus.s3.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.settings.ApplicationStateService;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import dev.phibus.s3.test.TestRunService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

@Service
public class TestScheduleService {
    private static final int CLAIM_SECONDS = 300;

    private final TestRunService testRunService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ApplicationStateService stateService;

    public TestScheduleService(TestRunService testRunService, JdbcTemplate jdbc, ObjectMapper objectMapper,
                               ApplicationStateService stateService) {
        this.testRunService = testRunService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.stateService = stateService;
    }

    public ScheduleView create(CreateScheduleRequest request) {
        validateCreateRequest(request);
        CronExpression cron = CronExpression.parse(request.cronExpression());
        ZoneId zone = zone(request.timeZone());
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        Instant nextRun = request.enabled() ? next(cron, zone, now) : null;
        jdbc.update("""
                INSERT INTO test_schedule
                    (id, name, enabled, cron_expression, time_zone, test_request_json, created_at, updated_at, next_run_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, request.name(), request.enabled(), request.cronExpression(), zone.getId(),
                writeRequest(request.testRequest()), timestamp(now), timestamp(now), timestamp(nextRun));
        return require(id);
    }

    public List<ScheduleView> list() {
        return jdbc.query("""
                SELECT id, name, enabled, cron_expression, time_zone, test_request_json,
                       created_at, last_run_at, next_run_at, last_test_run_id, last_error
                  FROM test_schedule
                 ORDER BY name, id
                """, this::map).stream().map(TestScheduleService::view).toList();
    }

    public ScheduleView setEnabled(UUID id, boolean enabled) {
        ScheduleView current = require(id);
        Instant nextRun = enabled
                ? next(CronExpression.parse(current.cronExpression()), zone(current.timeZone()), Instant.now()) : null;
        int changed = jdbc.update("""
                UPDATE test_schedule
                   SET enabled = ?, next_run_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, enabled, timestamp(nextRun), id);
        if (changed == 0) throw notFound(id);
        return require(id);
    }

    public void delete(UUID id) {
        if (jdbc.update("DELETE FROM test_schedule WHERE id = ?", id) == 0) throw notFound(id);
    }

    public ScheduleView runNow(UUID id) {
        execute(requireRecord(id), Instant.now());
        return require(id);
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatchDueSchedules() {
        if (stateService.current() != ApplicationStateService.State.READY) {
            return;
        }
        Instant now = Instant.now();
        List<UUID> due = jdbc.queryForList("""
                SELECT id
                  FROM test_schedule
                 WHERE enabled = TRUE AND next_run_at IS NOT NULL AND next_run_at <= ?
                 ORDER BY next_run_at
                 LIMIT 100
                """, UUID.class, timestamp(now));
        for (UUID id : due) {
            if (claim(id, now)) {
                execute(requireRecord(id), now);
            }
        }
    }

    private boolean claim(UUID id, Instant now) {
        return jdbc.update("""
                UPDATE test_schedule
                   SET next_run_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND enabled = TRUE AND next_run_at IS NOT NULL AND next_run_at <= ?
                """, timestamp(now.plusSeconds(CLAIM_SECONDS)), id, timestamp(now)) == 1;
    }

    private void execute(ScheduleRecord record, Instant now) {
        UUID runId = null;
        String error = null;
        try {
            TestRun run = testRunService.create(record.testRequest());
            runId = run.id();
        } catch (RuntimeException e) {
            error = safeMessage(e);
        }
        Instant nextRun = record.enabled()
                ? next(CronExpression.parse(record.cronExpression()), zone(record.timeZone()), now.plusSeconds(1)) : null;
        jdbc.update("""
                UPDATE test_schedule
                   SET last_run_at = ?, next_run_at = ?, last_test_run_id = ?, last_error = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, timestamp(now), timestamp(nextRun), runId, error, record.id());
    }

    private ScheduleView require(UUID id) {
        return view(requireRecord(id));
    }

    private ScheduleRecord requireRecord(UUID id) {
        List<ScheduleRecord> records = jdbc.query("""
                SELECT id, name, enabled, cron_expression, time_zone, test_request_json,
                       created_at, last_run_at, next_run_at, last_test_run_id, last_error
                  FROM test_schedule WHERE id = ?
                """, this::map, id);
        if (records.isEmpty()) throw notFound(id);
        return records.getFirst();
    }

    private ScheduleRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ScheduleRecord(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("enabled"),
                rs.getString("cron_expression"), rs.getString("time_zone"), instant(rs, "created_at"),
                instant(rs, "last_run_at"), instant(rs, "next_run_at"),
                rs.getObject("last_test_run_id", UUID.class), rs.getString("last_error"),
                readRequest(rs.getString("test_request_json")));
    }

    private static ScheduleView view(ScheduleRecord record) {
        return new ScheduleView(record.id(), record.name(), record.enabled(), record.cronExpression(),
                record.timeZone(), record.createdAt(), record.lastRunAt(), record.nextRunAt(),
                record.lastTestRunId(), record.lastError(), redact(record.testRequest()));
    }

    static TestRequest redact(TestRequest request) {
        return new TestRequest(request.endpoint(), request.bucket(), request.region(), null, null,
                request.pathStyleAccess(), request.objectKey(), request.objectSizeMiB(), request.partSizeMiB(),
                request.parallelism(), request.objectCount(), request.deleteAfterTest(), request.operation(),
                request.executionMode(), request.durationSeconds(), request.warmupSeconds(), request.workloadProfile(),
                request.workloadWeights(), request.targetOperationsPerSecond(), request.operationThreads(),
                request.profileId());
    }

    private static void validateCreateRequest(CreateScheduleRequest request) {
        if (request == null) throw new IllegalArgumentException("Schedule request is required");
        if (request.name() == null || request.name().isBlank())
            throw new IllegalArgumentException("Schedule name is required");
        if (request.testRequest() == null) throw new IllegalArgumentException("Scheduled test request is required");
        if (notBlank(request.testRequest().accessKey()) || notBlank(request.testRequest().secretKey())) {
            throw new IllegalArgumentException(
                    "Scheduled tests must use an S3 profile; accessKey and secretKey are not stored in schedules");
        }
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }

    private String writeRequest(TestRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize scheduled test request", e);
        }
    }

    private TestRequest readRequest(String json) {
        try {
            return objectMapper.readValue(json, TestRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize scheduled test request", e);
        }
    }

    private static ZoneId zone(String value) {
        return ZoneId.of(value == null || value.isBlank() ? "UTC" : value);
    }

    private static Instant next(CronExpression cron, ZoneId zone, Instant after) {
        ZonedDateTime result = cron.next(after.atZone(zone));
        return result == null ? null : result.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static IllegalArgumentException notFound(UUID id) {
        return new IllegalArgumentException("Schedule not found: " + id);
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 4000 ? message : message.substring(0, 4000);
    }

    public record CreateScheduleRequest(@NotBlank String name, boolean enabled,
                                        @NotBlank String cronExpression, String timeZone,
                                        @NotNull @Valid TestRequest testRequest) { }
    public record ScheduleView(UUID id, String name, boolean enabled, String cronExpression, String timeZone,
                               Instant createdAt, Instant lastRunAt, Instant nextRunAt, UUID lastTestRunId,
                               String lastError, TestRequest testRequest) { }
    private record ScheduleRecord(UUID id, String name, boolean enabled, String cronExpression, String timeZone,
                                  Instant createdAt, Instant lastRunAt, Instant nextRunAt, UUID lastTestRunId,
                                  String lastError, TestRequest testRequest) { }
}
