package dev.phibus.s3.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.ReloadableHikariDataSource;
import dev.phibus.s3.settings.S3ProfileService;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgreSqlMigrationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("s3perf")
            .withUsername("s3perf")
            .withPassword("s3perf-test");

    private static JdbcTemplate jdbc;
    private static S3ProfileService profiles;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        profiles = new S3ProfileService(jdbc);
    }

    @Test
    void allExpectedTablesAreCreatedAndMigrationsAreRepeatable() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(26);

        for (String table : new String[]{"agent", "test_run", "test_schedule", "security_audit_event", "s3_profile",
                "clickhouse_profile", "clickhouse_test_run", "clickhouse_replication_snapshot",
                "clickhouse_replicated_scenario_run", "clickhouse_failover_run"}) {
            Boolean exists = jdbc.queryForObject(
                    "SELECT to_regclass('public.' || ?) IS NOT NULL", Boolean.class, table);
            assertThat(exists).as("table %s", table).isTrue();
        }

        Integer latencyColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='test_run'
                   AND column_name IN ('latency_scope','latency_sample_count','api_request_count',
                       'api_request_p50_latency_ms','api_request_p95_latency_ms','api_request_p99_latency_ms',
                       'object_operation_count','object_p50_latency_ms','object_p95_latency_ms',
                       'object_p99_latency_ms')
                """, Integer.class);
        assertThat(latencyColumns).isEqualTo(10);

        Integer durableAgentColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='agent'
                   AND column_name IN ('agent_token_hash','enabled','desired_version','update_requested',
                       'update_completed_at','management_changed_at')
                """, Integer.class);
        assertThat(durableAgentColumns).isEqualTo(6);

        Integer durableDistributedRunColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='distributed_test'
                   AND column_name IN ('name','test_type','request_json','primary_endpoint','stop_reason',
                       'message','updated_at')
                """, Integer.class);
        assertThat(durableDistributedRunColumns).isEqualTo(7);

        Integer durableAssignmentColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='distributed_test_agent'
                   AND column_name IN ('assignment_json','rows_processed','operations_per_second',
                       'throughput_mibps','p50_latency_ms','p95_latency_ms','p99_latency_ms','message',
                       'latency_histogram','updated_at')
                """, Integer.class);
        assertThat(durableAssignmentColumns).isEqualTo(10);

        DataSource dataSource = jdbc.getDataSource();
        assertThat(dataSource).isNotNull();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void existingAndNewHistoryRowsDefaultToLocalInitiator() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into test_run(id,status,created_at,total_bytes,bytes_transferred,completed_parts,total_parts,
                    average_speed_mibps,p50_latency_ms,p95_latency_ms,p99_latency_ms,successful_parts,failed_parts,
                    delete_after_test,cleanup_successful)
                values (?, 'COMPLETED', current_timestamp, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false)
                """, id);

        assertThat(jdbc.queryForObject("select initiator from test_run where id=?", String.class, id))
                .isEqualTo("local");
    }

    @Test
    void durationCyclesCanPersistRepeatedObjectAndPartNumbers() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into test_run(id,status,created_at,total_bytes,bytes_transferred,completed_parts,total_parts,
                    average_speed_mibps,p50_latency_ms,p95_latency_ms,p99_latency_ms,successful_parts,failed_parts,
                    delete_after_test,cleanup_successful)
                values (?, 'COMPLETED', current_timestamp, 0, 0, 2, 2, 0, 0, 0, 0, 2, 0, false, false)
                """, id);
        jdbc.update("""
                insert into part_result(test_run_id,sequence_no,object_number,part_number,bytes,duration_ms,
                    speed_mibps,status) values (?,0,1,1,1024,10,1,'SUCCESS'),
                                                   (?,1,1,1,1024,11,1,'SUCCESS')
                """, id, id);

        assertThat(jdbc.queryForObject(
                "select count(*) from part_result where test_run_id=?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void localRunPersistsItsActualStopReason() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.load()).thenReturn(new BootstrapSettings(
                new BootstrapSettings.PostgreSqlSettings(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()),
                null, null, null));
        BootstrapSecretCodec codec = mock(BootstrapSecretCodec.class);
        when(codec.decrypt(POSTGRES.getPassword())).thenReturn(POSTGRES.getPassword());
        TestHistoryStore history = new TestHistoryStore(settings, codec);
        TestRun run = new TestRun(new TestRequest("http://s3:9000", "bucket", "us-east-1", null, null,
                true, "cancelled.bin", 5, 5, 1, 1, false, "UPLOAD"));

        run.cancel();
        history.save(run.snapshot());

        assertThat(jdbc.queryForObject("select stop_reason from test_run where id=?", String.class, run.id()))
                .isEqualTo("USER_CANCELLED");
        assertThat(history.get(run.id()).stopReason()).isEqualTo("USER_CANCELLED");
    }

    @Test
    void unifiedHistoryCombinesAllEnginesAndDeduplicatesDistributedResults() {
        String marker = "unified-" + UUID.randomUUID();
        UUID s3Id = UUID.randomUUID();
        UUID clickHouseId = UUID.randomUUID();
        UUID kafkaId = UUID.randomUUID();
        UUID distributedId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO test_run(id,status,created_at,started_at,finished_at,endpoint,bucket,region,object_key,
                    total_bytes,bytes_transferred,completed_parts,total_parts,average_speed_mibps,
                    p50_latency_ms,p95_latency_ms,p99_latency_ms,successful_parts,failed_parts,
                    delete_after_test,cleanup_successful,message,operation,stop_reason,initiator,latency_scope)
                VALUES (?, 'COMPLETED', current_timestamp - interval '4 seconds',
                    current_timestamp - interval '3 seconds', current_timestamp - interval '2 seconds',
                    ?, ?, 'us-east-1', ?, 1024, 1024, 1, 1, 1, 1, 2, 3, 1, 0,
                    false, true, 'done', 'UPLOAD', 'NORMAL', 'local', 'MULTIPART_PART')
                """, s3Id, "http://" + marker, marker, marker + ".bin");
        insertClickHouseRun(clickHouseId, marker);
        jdbc.update("""
                INSERT INTO kafka_test_run(id,profile_id,topic,status,started_at,finished_at,duration_ms,
                    requested_messages,sent_messages,sent_bytes,producer_threads,message_size_bytes,
                    throughput_messages_sec,throughput_mib_sec,latency_avg_ms,latency_p95_ms,
                    latency_p99_ms,latency_max_ms,error_count,retry_count,error_message,config_json,initiator)
                VALUES (?, NULL, ?, 'COMPLETED_WITH_ERRORS', current_timestamp - interval '3 seconds',
                    current_timestamp - interval '2 seconds', 1000, 10, 9, 900, 1, 100,
                    9, 0.001, 1, 2, 3, 4, 1, 0, 'one failed message',
                    'acks=all;compression=none;batchSize=100;lingerMs=1;idempotence=true', 'local')
                """, kafkaId, marker);
        jdbc.update("""
                INSERT INTO distributed_test(id,status,requested_agents,started_at,finished_at,created_at,
                    aggregate_bytes,aggregate_operations,aggregate_errors,name,test_type,request_json,
                    primary_endpoint,stop_reason,message,updated_at)
                VALUES (?, 'COMPLETED', 2, current_timestamp - interval '3 seconds',
                    current_timestamp - interval '1 second', current_timestamp - interval '4 seconds',
                    2048, 20, 0, ?, 'KAFKA', '{}', 'kafka:9092', 'NORMAL', 'done', current_timestamp)
                """, distributedId, marker + " distributed");
        jdbc.update("""
                INSERT INTO distributed_test_agent(distributed_test_id,agent_id,status,started_at,finished_at,
                    bytes_transferred,operations,errors,rows_processed,operations_per_second,
                    throughput_mibps,p50_latency_ms,p95_latency_ms,p99_latency_ms,updated_at)
                VALUES (?, ?, 'COMPLETED', current_timestamp - interval '3 seconds', current_timestamp,
                    2048, 20, 0, 0, 10, 2, 1, 2, 3, current_timestamp)
                """, distributedId, UUID.randomUUID());
        insertClickHouseRun(distributedId, marker);

        UnifiedRunHistoryStore store = new UnifiedRunHistoryStore(jdbc);
        UnifiedRunHistoryStore.Page all = store.search(new UnifiedRunHistoryStore.Filter(
                null, null, null, marker, null, null, 0, 20));

        assertThat(all.totalElements()).isEqualTo(4);
        assertThat(all.items()).extracting(UnifiedRunHistoryStore.RunHistoryRow::testType)
                .containsExactlyInAnyOrder(TestType.S3, TestType.CLICKHOUSE, TestType.KAFKA, TestType.KAFKA);
        assertThat(all.items()).filteredOn(row -> row.id().equals(distributedId)).singleElement()
                .satisfies(row -> {
                    assertThat(row.scope()).isEqualTo(RunScope.DISTRIBUTED);
                    assertThat(row.participants()).isEqualTo(2);
                    assertThat(row.operationsPerSecond()).isEqualTo(10);
                    assertThat(row.rerunnable()).isFalse();
                });
        assertThat(all.items()).filteredOn(row -> row.id().equals(kafkaId)).singleElement()
                .satisfies(row -> assertThat(row.rerunnable()).isFalse());
        assertThat(all.items()).filteredOn(row -> row.id().equals(s3Id)).singleElement()
                .satisfies(row -> assertThat(row.rerunnable()).isTrue());

        UnifiedRunHistoryStore.Page filtered = store.search(new UnifiedRunHistoryStore.Filter(
                RunScope.LOCAL, TestType.CLICKHOUSE, RunStatus.COMPLETED, marker, null, null, 0, 20));
        assertThat(filtered.items()).extracting(UnifiedRunHistoryStore.RunHistoryRow::id)
                .containsExactly(clickHouseId);
        assertThat(store.get(s3Id).sourceDetailUrl()).isEqualTo("/history/" + s3Id);
    }

    @Test
    void profileCrudAndSingleDefaultConstraintWorkAgainstPostgreSql() {
        S3ProfileService.Profile first = profiles.create(request("primary", "http://minio-a:9000", true));
        S3ProfileService.Profile second = profiles.create(request("secondary", "http://minio-b:9000", false));

        assertThat(profiles.defaultProfile()).extracting(S3ProfileService.Profile::id).isEqualTo(first.id());
        assertThat(profiles.list()).hasSize(2);

        profiles.makeDefault(second.id());
        assertThat(profiles.defaultProfile()).extracting(S3ProfileService.Profile::id).isEqualTo(second.id());
        assertThat(profiles.get(first.id()).defaultProfile()).isFalse();

        S3ProfileService.Profile clone = profiles.cloneProfile(second.id(), "secondary-copy");
        assertThat(clone.defaultProfile()).isFalse();
        assertThat(clone.endpoint()).isEqualTo(second.endpoint());

        assertThatThrownBy(() -> profiles.delete(second.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Default");

        profiles.delete(first.id());
        profiles.delete(clone.id());
        assertThat(profiles.list()).extracting(S3ProfileService.Profile::id).containsExactly(second.id());
    }

    @Test
    void hikariPoolReloadsAfterBootstrapConnectionSettingsChangeAndPublishesMetrics() throws Exception {
        String reloadUser = "rc18_reload";
        String reloadPassword = "rc18-reload-password";
        jdbc.execute("CREATE ROLE " + reloadUser + " LOGIN PASSWORD '" + reloadPassword + "'");

        AtomicReference<BootstrapSettings.PostgreSqlSettings> configured = new AtomicReference<>(
                new BootstrapSettings.PostgreSqlSettings(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        SettingsService settings = mock(SettingsService.class);
        when(settings.load()).thenAnswer(ignored -> new BootstrapSettings(
                configured.get(), null, null, null));
        BootstrapSecretCodec codec = mock(BootstrapSecretCodec.class);
        when(codec.decrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0, String.class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try (ReloadableHikariDataSource dataSource = new ReloadableHikariDataSource(
                settings, codec, registry, 4, 0, 5_000, 1_000, 10_000, 30_000, 0)) {
            assertThat(currentUser(dataSource)).isEqualTo(POSTGRES.getUsername());
            String firstPool = dataSource.poolName();

            configured.set(new BootstrapSettings.PostgreSqlSettings(
                    POSTGRES.getJdbcUrl(), reloadUser, reloadPassword));
            assertThat(currentUser(dataSource)).isEqualTo(reloadUser);
            String secondPool = dataSource.poolName();

            assertThat(secondPool).isNotEqualTo(firstPool);
            assertThat(registry.getMeters()).anyMatch(meter -> meter.getId().getName().startsWith("hikaricp.")
                    && secondPool.equals(meter.getId().getTag("pool")));
        }
    }

    private static String currentUser(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_user")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static void insertClickHouseRun(UUID id, String marker) {
        jdbc.update("""
                INSERT INTO clickhouse_test_run(id,profile_id,endpoint,table_name,operation,status,created_at,
                    started_at,finished_at,concurrency,batch_size,requested_rows,requested_queries,
                    duration_seconds,warmup_seconds,payload_bytes,auto_create_table,rows_processed,
                    bytes_processed,queries,errors,rows_per_second,mib_per_second,queries_per_second,
                    p50_latency_ms,p95_latency_ms,p99_latency_ms,message)
                VALUES (?, ?, ?, ?, 'SELECT', 'COMPLETED', current_timestamp - interval '4 seconds',
                    current_timestamp - interval '3 seconds', current_timestamp - interval '2 seconds',
                    1, 100, 100, 1, 0, 0, 128, true, 100, 12800, 1, 0, 100, 0.01, 1, 1, 2, 3, 'done')
                """, id, UUID.randomUUID(), "http://" + marker, marker);
    }

    private static S3ProfileService.ProfileRequest request(String name, String endpoint, boolean defaultProfile) {
        return new S3ProfileService.ProfileRequest(name, endpoint, "us-east-1", "integration-bucket",
                true, "VAULT", "profiles/" + UUID.randomUUID(), "accessKey", "secretKey",
                "sessionToken", null, defaultProfile);
    }
}
