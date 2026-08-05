package dev.phibus.s3.schedule;

import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

@Service
public class TestScheduleService {
    private final Map<UUID, ScheduleRecord> schedules = new ConcurrentHashMap<>();
    private final TestRunService testRunService;

    public TestScheduleService(TestRunService testRunService) {
        this.testRunService = testRunService;
    }

    public ScheduleView create(CreateScheduleRequest request) {
        CronExpression cron = CronExpression.parse(request.cronExpression());
        ZoneId zone = ZoneId.of(request.timeZone() == null || request.timeZone().isBlank() ? "UTC" : request.timeZone());
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        ScheduleRecord record = new ScheduleRecord(id, request.name(), request.enabled(), request.cronExpression(), zone,
                request.testRequest(), now, null, next(cron, zone, now), null, null);
        schedules.put(id, record);
        return view(record);
    }

    public List<ScheduleView> list() {
        return schedules.values().stream().map(this::view)
                .sorted(Comparator.comparing(ScheduleView::name)).toList();
    }

    public ScheduleView setEnabled(UUID id, boolean enabled) {
        ScheduleRecord current = require(id);
        Instant nextRun = enabled ? next(CronExpression.parse(current.cronExpression()), current.timeZone(), Instant.now()) : null;
        ScheduleRecord updated = new ScheduleRecord(current.id(), current.name(), enabled, current.cronExpression(),
                current.timeZone(), current.testRequest(), current.createdAt(), current.lastRunAt(), nextRun,
                current.lastTestRunId(), current.lastError());
        schedules.put(id, updated);
        return view(updated);
    }

    public void delete(UUID id) {
        if (schedules.remove(id) == null) throw new IllegalArgumentException("Schedule not found: " + id);
    }

    public ScheduleView runNow(UUID id) {
        execute(require(id), Instant.now());
        return view(require(id));
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatchDueSchedules() {
        Instant now = Instant.now();
        schedules.values().stream()
                .filter(ScheduleRecord::enabled)
                .filter(record -> record.nextRunAt() != null && !record.nextRunAt().isAfter(now))
                .forEach(record -> execute(record, now));
    }

    private void execute(ScheduleRecord record, Instant now) {
        UUID runId = null;
        String error = null;
        try {
            TestRun run = testRunService.create(record.testRequest());
            runId = run.id();
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        Instant nextRun = record.enabled()
                ? next(CronExpression.parse(record.cronExpression()), record.timeZone(), now.plusSeconds(1)) : null;
        schedules.put(record.id(), new ScheduleRecord(record.id(), record.name(), record.enabled(), record.cronExpression(),
                record.timeZone(), record.testRequest(), record.createdAt(), now, nextRun, runId, error));
    }

    private ScheduleRecord require(UUID id) {
        ScheduleRecord record = schedules.get(id);
        if (record == null) throw new IllegalArgumentException("Schedule not found: " + id);
        return record;
    }

    private static Instant next(CronExpression cron, ZoneId zone, Instant after) {
        ZonedDateTime result = cron.next(after.atZone(zone));
        return result == null ? null : result.toInstant();
    }

    private ScheduleView view(ScheduleRecord record) {
        return new ScheduleView(record.id(), record.name(), record.enabled(), record.cronExpression(),
                record.timeZone().getId(), record.createdAt(), record.lastRunAt(), record.nextRunAt(),
                record.lastTestRunId(), record.lastError(), record.testRequest());
    }

    public record CreateScheduleRequest(String name, boolean enabled, String cronExpression,
                                        String timeZone, TestRequest testRequest) { }
    private record ScheduleRecord(UUID id, String name, boolean enabled, String cronExpression, ZoneId timeZone,
                                  TestRequest testRequest, Instant createdAt, Instant lastRunAt, Instant nextRunAt,
                                  UUID lastTestRunId, String lastError) { }
    public record ScheduleView(UUID id, String name, boolean enabled, String cronExpression, String timeZone,
                               Instant createdAt, Instant lastRunAt, Instant nextRunAt, UUID lastTestRunId,
                               String lastError, TestRequest testRequest) { }
}
