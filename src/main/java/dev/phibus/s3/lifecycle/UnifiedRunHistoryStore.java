package dev.phibus.s3.lifecycle;

import dev.phibus.s3.test.TestType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class UnifiedRunHistoryStore {
    private static final int MAX_PAGE_SIZE = 200;
    private static final String HISTORY_CTE = """
            WITH unified_history AS (
                SELECT tr.id,
                       'S3'::text AS test_type,
                       'LOCAL'::text AS scope,
                       tr.status,
                       tr.created_at,
                       tr.started_at,
                       tr.finished_at,
                       concat('S3 ', tr.operation, ' ', coalesce(tr.bucket, '')) AS name,
                       tr.operation::text AS operation,
                       concat_ws(' / ', nullif(tr.endpoint, ''), nullif(tr.bucket, '')) AS target,
                       coalesce(nullif(tr.initiator, ''), 'local') AS initiator,
                       tr.completed_parts::bigint AS completed_operations,
                       0::bigint AS rows_processed,
                       tr.bytes_transferred::bigint AS bytes_transferred,
                       CASE WHEN tr.started_at IS NOT NULL AND tr.finished_at > tr.started_at
                            THEN tr.completed_parts / extract(epoch FROM (tr.finished_at - tr.started_at))
                            ELSE 0 END::double precision AS operations_per_second,
                       tr.average_speed_mibps::double precision AS throughput_mibps,
                       tr.p50_latency_ms::double precision AS p50_latency_ms,
                       tr.p95_latency_ms::double precision AS p95_latency_ms,
                       tr.p99_latency_ms::double precision AS p99_latency_ms,
                       tr.failed_parts::bigint AS errors,
                       tr.stop_reason::text AS stop_reason,
                       tr.message,
                       1::integer AS participants,
                       tr.latency_scope::text AS latency_scope,
                       '/history/' || tr.id::text AS source_detail_url,
                       tr.status IN ('COMPLETED', 'FAILED', 'CANCELLED') AS rerunnable
                  FROM test_run tr
                 WHERE coalesce(tr.initiator, 'local') = 'local'

                UNION ALL

                SELECT ch.id,
                       'CLICKHOUSE'::text,
                       'LOCAL'::text,
                       ch.status,
                       ch.created_at,
                       ch.started_at,
                       ch.finished_at,
                       concat('ClickHouse ', ch.operation, ' ', ch.table_name),
                       ch.operation::text,
                       concat_ws(' / ', nullif(ch.endpoint, ''), nullif(ch.table_name, '')),
                       'local'::text,
                       ch.queries::bigint,
                       ch.rows_processed::bigint,
                       ch.bytes_processed::bigint,
                       ch.queries_per_second::double precision,
                       ch.mib_per_second::double precision,
                       ch.p50_latency_ms::double precision,
                       ch.p95_latency_ms::double precision,
                       ch.p99_latency_ms::double precision,
                       ch.errors::bigint,
                       CASE ch.status
                           WHEN 'FAILED' THEN 'ERROR'
                           WHEN 'CANCELLED' THEN CASE WHEN lower(coalesce(ch.message, '')) LIKE '%deadline%'
                                                   THEN 'DEADLINE_EXCEEDED' ELSE 'USER_CANCELLED' END
                           WHEN 'COMPLETED' THEN 'NORMAL'
                           ELSE NULL
                       END,
                       ch.message,
                       1::integer,
                       'CLICKHOUSE_QUERY'::text,
                       '/clickhouse/history/' || ch.id::text,
                       ch.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                  FROM clickhouse_test_run ch
                 WHERE NOT EXISTS (SELECT 1 FROM distributed_test distributed WHERE distributed.id = ch.id)

                UNION ALL

                SELECT kafka.id,
                       'KAFKA'::text,
                       'LOCAL'::text,
                       kafka.status,
                       kafka.started_at,
                       kafka.started_at,
                       kafka.finished_at,
                       concat('Kafka ', kafka.topic),
                       'PRODUCE'::text,
                       kafka.topic::text,
                       coalesce(nullif(kafka.initiator, ''), 'local'),
                       kafka.sent_messages::bigint,
                       0::bigint,
                       kafka.sent_bytes::bigint,
                       coalesce(kafka.throughput_messages_sec, 0)::double precision,
                       coalesce(kafka.throughput_mib_sec, 0)::double precision,
                       0::double precision,
                       coalesce(kafka.latency_p95_ms, 0)::double precision,
                       coalesce(kafka.latency_p99_ms, 0)::double precision,
                       kafka.error_count::bigint,
                       CASE kafka.status
                           WHEN 'FAILED' THEN 'ERROR'
                           WHEN 'CANCELLED' THEN CASE WHEN lower(coalesce(kafka.error_message, '')) LIKE '%deadline%'
                                                   THEN 'DEADLINE_EXCEEDED' ELSE 'USER_CANCELLED' END
                           WHEN 'COMPLETED' THEN 'NORMAL'
                           WHEN 'COMPLETED_WITH_ERRORS' THEN 'NORMAL'
                           ELSE NULL
                       END,
                       kafka.error_message,
                       1::integer,
                       'KAFKA_PRODUCE_ACK'::text,
                       NULL::text,
                       kafka.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')
                           AND kafka.profile_id IS NOT NULL
                           AND coalesce(kafka.requested_messages, 0) > 0
                  FROM kafka_test_run kafka

                UNION ALL

                SELECT distributed.id,
                       distributed.test_type::text,
                       'DISTRIBUTED'::text,
                       distributed.status,
                       distributed.created_at,
                       distributed.started_at,
                       distributed.finished_at,
                       coalesce(distributed.name, concat('Distributed ', distributed.test_type, ' test')),
                       distributed.test_type::text,
                       coalesce(distributed.primary_endpoint, ''),
                       'coordinator'::text,
                       distributed.aggregate_operations::bigint,
                       coalesce(agent.rows_processed, 0)::bigint,
                       distributed.aggregate_bytes::bigint,
                       coalesce(agent.operations_per_second, 0)::double precision,
                       coalesce(agent.throughput_mibps, 0)::double precision,
                       coalesce(agent.p50_latency_ms, 0)::double precision,
                       coalesce(agent.p95_latency_ms, 0)::double precision,
                       coalesce(agent.p99_latency_ms, 0)::double precision,
                       distributed.aggregate_errors::bigint,
                       distributed.stop_reason::text,
                       distributed.message,
                       distributed.requested_agents::integer,
                       CASE distributed.test_type
                           WHEN 'CLICKHOUSE' THEN 'CLICKHOUSE_QUERY'
                           WHEN 'KAFKA' THEN 'KAFKA_PRODUCE_ACK'
                           ELSE 'S3_RECORDED_STEP'
                       END::text,
                       NULL::text,
                       distributed.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')
                           AND btrim(coalesce(distributed.request_json, '')) NOT IN ('', '{}')
                           AND EXISTS (SELECT 1 FROM distributed_test_agent repeat_agent
                                        WHERE repeat_agent.distributed_test_id = distributed.id)
                  FROM distributed_test distributed
                  LEFT JOIN LATERAL (
                       SELECT sum(progress.rows_processed) AS rows_processed,
                              sum(progress.operations_per_second) AS operations_per_second,
                              sum(progress.throughput_mibps) AS throughput_mibps,
                              max(progress.p50_latency_ms) AS p50_latency_ms,
                              max(progress.p95_latency_ms) AS p95_latency_ms,
                              max(progress.p99_latency_ms) AS p99_latency_ms
                         FROM distributed_test_agent progress
                        WHERE progress.distributed_test_id = distributed.id
                  ) agent ON TRUE
            )
            """;

    private final JdbcTemplate jdbc;

    public UnifiedRunHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page search(Filter filter) {
        Filter effective = filter == null ? new Filter(null, null, null, null, null, null, 0, 20) : filter;
        int page = Math.max(0, effective.page());
        int size = Math.max(1, Math.min(effective.size(), MAX_PAGE_SIZE));
        List<Object> parameters = new ArrayList<>();
        String where = where(effective, parameters);
        Long total = jdbc.queryForObject(HISTORY_CTE + "SELECT count(*) FROM unified_history" + where,
                Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add(page * size);
        List<RunHistoryRow> items = jdbc.query(HISTORY_CTE + "SELECT * FROM unified_history" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::map, pageParameters.toArray());
        long count = total == null ? 0 : total;
        return new Page(items, page, size, count, (int) Math.ceil(count / (double) size));
    }

    public RunHistoryRow get(UUID id) {
        if (id == null) return null;
        List<RunHistoryRow> rows = jdbc.query(HISTORY_CTE
                + "SELECT * FROM unified_history WHERE id = ? "
                + "ORDER BY CASE scope WHEN 'DISTRIBUTED' THEN 0 ELSE 1 END LIMIT 1", this::map, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String where(Filter filter, List<Object> parameters) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        if (filter.scope() != null) {
            sql.append(" AND scope = ?");
            parameters.add(filter.scope().name());
        }
        if (filter.testType() != null) {
            sql.append(" AND test_type = ?");
            parameters.add(filter.testType().name());
        }
        if (filter.status() != null) {
            sql.append(" AND status = ?");
            parameters.add(filter.status().name());
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" AND lower(concat_ws(' ', id::text, name, operation, target, message)) LIKE ?");
            parameters.add("%" + filter.query().trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (filter.from() != null) {
            sql.append(" AND created_at >= ?");
            parameters.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND created_at < ?");
            parameters.add(Timestamp.from(filter.to()));
        }
        return sql.toString();
    }

    private RunHistoryRow map(ResultSet result, int row) throws SQLException {
        RunStatus status = RunStatus.parse(result.getString("status"));
        RunStopReason stopReason = status.terminal()
                ? RunStopReason.parseNullable(result.getString("stop_reason")) : null;
        return new RunHistoryRow(result.getObject("id", UUID.class),
                TestType.valueOf(result.getString("test_type")), RunScope.valueOf(result.getString("scope")),
                status, instant(result, "created_at"), instant(result, "started_at"),
                instant(result, "finished_at"), result.getString("name"), result.getString("operation"),
                result.getString("target"), result.getString("initiator"),
                result.getLong("completed_operations"),
                result.getLong("rows_processed"), result.getLong("bytes_transferred"),
                result.getDouble("operations_per_second"), result.getDouble("throughput_mibps"),
                result.getDouble("p50_latency_ms"), result.getDouble("p95_latency_ms"),
                result.getDouble("p99_latency_ms"), result.getLong("errors"), stopReason,
                result.getString("message"), result.getInt("participants"),
                result.getString("latency_scope"), result.getString("source_detail_url"),
                result.getBoolean("rerunnable"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record Filter(RunScope scope, TestType testType, RunStatus status, String query,
                         Instant from, Instant to, int page, int size) { }

    public record Page(List<RunHistoryRow> items, int page, int size, long totalElements, int totalPages) { }

    public record RunHistoryRow(UUID id, TestType testType, RunScope scope, RunStatus status,
                                Instant createdAt, Instant startedAt, Instant finishedAt, String name,
                                String operation, String target, String initiator, long completedOperations,
                                long rowsProcessed,
                                long bytesTransferred, double operationsPerSecond, double throughputMiBps,
                                double p50LatencyMs, double p95LatencyMs, double p99LatencyMs, long errors,
                                RunStopReason stopReason, String message, int participants, String latencyScope,
                                String sourceDetailUrl, boolean rerunnable) {
        public boolean s3AnalyticsCompatible() {
            return scope == RunScope.LOCAL && testType == TestType.S3;
        }

        public long durationMillis() {
            return startedAt == null || finishedAt == null
                    ? 0 : Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
        }
    }
}
