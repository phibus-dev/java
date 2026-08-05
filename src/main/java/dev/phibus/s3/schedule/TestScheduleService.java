package dev.phibus.s3.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRun;
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

    public TestScheduleService(TestRunService testRunService, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.testRunService = testRunService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ScheduleView create(CreateScheduleRequest request) {
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
                """, this::map);
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
        execute(require(id), Instant.now());
        return require(id);
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatchDueSchedules() {
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
                execute(require(id), now);
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

    private void execute(ScheduleView record, Instant now) {
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
        List<ScheduleView> records = jdbc.query("""
                SELECT id, name, enabled, cron_expression, time_zone, test_request_json,
                       created_at, last_run_at, next_run_at, last_test_run_id, last_error
                  FROM test_schedule WHERE id = ?
                """, this::map, id);
        if (records.isEmpty()) throw notFound(id);
        return records.getFirst();
    }

    private ScheduleView map(ResultSet rs, int rowNum) throws SQLException {
        return new ScheduleView(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("enabled"),
                rs.getString("cron_expression"), rs.getString("time_zone"), instant(rs, "created_at"),
                instant(rs, "last_run_at"), instant(rs, "next_run_at"),
                rs.getObject("last_test_run_id", UUID.class), rs.getString("last_error"),
                readRequest(rs.getString("test_request_json")));
    }

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

    public record CreateScheduleRequest(String name, boolean enabled, String cronExpression,
                                        String timeZone, TestRequest testRequest) { }
    public record ScheduleView(UUID id, String name, boolean enabled, String cronExpression, String timeZone,
                               Instant createdAt, Instant lastRunAt, Instant nextRunAt, UUID lastTestRunId,
                               String lastError, TestRequest testRequest) { }
}
