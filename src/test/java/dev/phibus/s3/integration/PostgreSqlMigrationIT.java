package dev.phibus.s3.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.phibus.s3.settings.S3ProfileService;
import java.util.UUID;
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
        assertThat(applied).isGreaterThanOrEqualTo(11);

        for (String table : new String[]{"test_run", "test_schedule", "security_audit_event", "s3_profile",
                "clickhouse_profile", "clickhouse_test_run", "clickhouse_replication_snapshot"}) {
            Boolean exists = jdbc.queryForObject(
                    "SELECT to_regclass('public.' || ?) IS NOT NULL", Boolean.class, table);
            assertThat(exists).as("table %s", table).isTrue();
        }

        DataSource dataSource = jdbc.getDataSource();
        assertThat(dataSource).isNotNull();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
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

    private static S3ProfileService.ProfileRequest request(String name, String endpoint, boolean defaultProfile) {
        return new S3ProfileService.ProfileRequest(name, endpoint, "us-east-1", "integration-bucket",
                true, "VAULT", "profiles/" + UUID.randomUUID(), "accessKey", "secretKey",
                "sessionToken", null, defaultProfile);
    }
}
