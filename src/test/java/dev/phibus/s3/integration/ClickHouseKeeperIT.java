package dev.phibus.s3.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.phibus.s3.clickhouse.ClickHouseConnectionProvider;
import dev.phibus.s3.clickhouse.ClickHouseConnectionSpec;
import dev.phibus.s3.clickhouse.ClickHouseLoadTestEngine;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.clickhouse.ClickHouseTestRun;
import dev.phibus.s3.runtime.RuntimeResourceManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers(disabledWithoutDocker = true)
class ClickHouseKeeperIT {
    private static final Network NETWORK = Network.SHARED;

    @Container
    private static final GenericContainer<?> KEEPER = new GenericContainer<>("clickhouse/clickhouse-keeper:25.8-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("keeper")
            .withExposedPorts(9181)
            .withCopyFileToContainer(MountableFile.forClasspathResource("clickhouse/keeper-server.xml"),
                    "/etc/clickhouse-keeper/keeper_config.xml")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    private static final ClickHouseContainer CLICKHOUSE = new ClickHouseContainer("clickhouse/clickhouse-server:25.8-alpine")
            .withNetwork(NETWORK)
            .dependsOn(KEEPER)
            .withCopyFileToContainer(MountableFile.forClasspathResource("clickhouse/keeper.xml"),
                    "/etc/clickhouse-server/config.d/keeper.xml")
            .withStartupTimeoutSeconds(120);

    @Test
    void loadEngineUsesRealClickHouseAndReplicatedTableUsesKeeper() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<dev.phibus.s3.clickhouse.ClickHouseProfileService> profiles = mock(ObjectProvider.class);
        ClickHouseConnectionProvider connections = new ClickHouseConnectionProvider(profiles);
        String endpoint = "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
        ClickHouseConnectionSpec spec = new ClickHouseConnectionSpec(endpoint, CLICKHOUSE.getDatabaseName(),
                CLICKHOUSE.getUsername(), CLICKHOUSE.getPassword(), 10_000, 30);

        String replicatedTable = "rc18_keeper_" + UUID.randomUUID().toString().replace("-", "");
        String keeperPath = "/clickhouse/tables/01/" + replicatedTable;
        try (Connection connection = connections.open(spec); Statement statement = connection.createStatement()) {
            awaitKeeperReady(statement);
            executeWithKeeperRetry(statement, "CREATE TABLE IF NOT EXISTS " + replicatedTable
                    + " (id UInt64, payload String) ENGINE=ReplicatedMergeTree('" + keeperPath
                    + "', 'replica1') ORDER BY id");
            awaitWritableReplica(statement, replicatedTable);
            statement.execute("SET insert_keeper_max_retries=10");
            executeWithKeeperRetry(statement,
                    "INSERT INTO " + replicatedTable + " VALUES (1, 'keeper-ok')");
            try (ResultSet result = statement.executeQuery("SELECT count() FROM " + replicatedTable)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1);
            }
            try (ResultSet result = statement.executeQuery("SELECT count() FROM system.zookeeper WHERE path='/'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isGreaterThan(0);
            }
        }

        UUID profileId = UUID.randomUUID();
        ClickHouseTestRequest request = new ClickHouseTestRequest(profileId, endpoint, "rc18_load",
                "INSERT_SELECT", 2, 10, 100, 0, 0, 32, 2L, true);
        ClickHouseTestRun run = new ClickHouseTestRun(request, endpoint);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             RuntimeResourceManager resources = new RuntimeResourceManager(2, 8, 60,
                     new SimpleMeterRegistry())) {
            new ClickHouseLoadTestEngine(connections, executor, resources).execute(run, spec);
        }

        assertThat(run.snapshot().status()).isEqualTo(ClickHouseTestRun.Status.COMPLETED);
        assertThat(run.snapshot().rows()).isGreaterThanOrEqualTo(100);
        assertThat(run.snapshot().queries()).isGreaterThanOrEqualTo(2);
    }

    private static void awaitKeeperReady(Statement statement) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        SQLException lastError = null;
        while (System.nanoTime() < deadline) {
            try (ResultSet result = statement.executeQuery("SELECT count() FROM system.zookeeper WHERE path='/'")) {
                if (result.next()) return;
            } catch (SQLException error) {
                lastError = error;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("ClickHouse did not establish a Keeper session", lastError);
    }

    private static void executeWithKeeperRetry(Statement statement, String sql) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try {
                statement.execute(sql);
                return;
            } catch (SQLException error) {
                if (!isKeeperStartupError(error) || System.nanoTime() >= deadline) throw error;
                Thread.sleep(250);
            }
        }
    }

    private static boolean isKeeperStartupError(SQLException error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("Code: 999")) return true;
        }
        return error.getErrorCode() == 999;
    }

    private static void awaitWritableReplica(Statement statement, String table) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String diagnostic = "replica row is not visible";
        while (System.nanoTime() < deadline) {
            try (ResultSet result = statement.executeQuery(
                    "SELECT is_readonly, is_session_expired, zookeeper_exception "
                            + "FROM system.replicas WHERE database=currentDatabase() AND table='" + table + "'")) {
                if (result.next()) {
                    boolean readOnly = result.getBoolean(1);
                    boolean sessionExpired = result.getBoolean(2);
                    diagnostic = "is_readonly=" + readOnly + ", is_session_expired=" + sessionExpired
                            + ", zookeeper_exception=" + result.getString(3);
                    if (!readOnly && !sessionExpired) return;
                }
            } catch (SQLException error) {
                diagnostic = error.getMessage();
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("ClickHouse replica did not become writable: " + diagnostic);
    }
}
