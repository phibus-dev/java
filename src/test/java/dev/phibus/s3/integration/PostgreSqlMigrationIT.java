package dev.phibus.s3.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.ReloadableHikariDataSource;
import dev.phibus.s3.settings.S3ProfileService;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRun;
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

    private static S3ProfileService.ProfileRequest request(String name, String endpoint, boolean defaultProfile) {
        return new S3ProfileService.ProfileRequest(name, endpoint, "us-east-1", "integration-bucket",
                true, "VAULT", "profiles/" + UUID.randomUUID(), "accessKey", "secretKey",
                "sessionToken", null, defaultProfile);
    }
}
