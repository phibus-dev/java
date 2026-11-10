package dev.phibus.s3.settings;

import dev.phibus.s3.distributed.ApplicationMode;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BootstrapJdbcConfiguration {

    @Bean
    DataSource bootstrapDataSource(SettingsService settingsService, BootstrapSecretCodec codec,
                                   MeterRegistry meterRegistry,
                                   @Value("${s3perf.postgresql.pool.maximum-size:16}") int maximumPoolSize,
                                   @Value("${s3perf.postgresql.pool.minimum-idle:1}") int minimumIdle,
                                   @Value("${s3perf.postgresql.pool.connection-timeout-ms:10000}") long connectionTimeoutMs,
                                   @Value("${s3perf.postgresql.pool.validation-timeout-ms:3000}") long validationTimeoutMs,
                                   @Value("${s3perf.postgresql.pool.idle-timeout-ms:600000}") long idleTimeoutMs,
                                   @Value("${s3perf.postgresql.pool.max-lifetime-ms:1800000}") long maxLifetimeMs,
                                   @Value("${s3perf.postgresql.pool.leak-detection-ms:0}") long leakDetectionMs,
                                   @Value("${s3perf.application-mode:COORDINATOR}") String applicationMode) {
        if (ApplicationMode.from(applicationMode) == ApplicationMode.AGENT) {
            return new AgentDisabledDataSource();
        }
        return new ReloadableHikariDataSource(settingsService, codec, meterRegistry, maximumPoolSize, minimumIdle,
                connectionTimeoutMs, validationTimeoutMs, idleTimeoutMs, maxLifetimeMs, leakDetectionMs);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource bootstrapDataSource,
                              @Value("${s3perf.postgresql.query-timeout-seconds:30}") int queryTimeoutSeconds) {
        JdbcTemplate template = new JdbcTemplate(bootstrapDataSource);
        template.setQueryTimeout(Math.max(1, queryTimeoutSeconds));
        return template;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource bootstrapDataSource,
            @Value("${s3perf.postgresql.transaction-timeout-seconds:60}") int transactionTimeoutSeconds) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(bootstrapDataSource);
        manager.setDefaultTimeout(Math.max(1, transactionTimeoutSeconds));
        return manager;
    }

    @Bean
    ApplicationRunner migrateConfiguredPostgreSql(SettingsService settingsService,
                                                   DataSource bootstrapDataSource,
                                                   @Value("${s3perf.application-mode:COORDINATOR}") String applicationMode) {
        return arguments -> {
            if (ApplicationMode.from(applicationMode) == ApplicationMode.AGENT) {
                return;
            }
            BootstrapSettings.PostgreSqlSettings postgresql = settingsService.load().postgresql();
            if (!postgresql.configured()) {
                return;
            }
            Flyway.configure()
                    .dataSource(bootstrapDataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
        };
    }

    private static final class AgentDisabledDataSource implements DataSource {
        private SQLException disabled() {
            return new SQLException("PostgreSQL persistence is disabled in AGENT mode");
        }
        @Override public Connection getConnection() throws SQLException { throw disabled(); }
        @Override public Connection getConnection(String username, String password) throws SQLException { throw disabled(); }
        @Override public PrintWriter getLogWriter() { return DriverManager.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter out) { DriverManager.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) { DriverManager.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() { return DriverManager.getLoginTimeout(); }
        @Override public Logger getParentLogger() { return Logger.getLogger("dev.phibus.s3.jdbc.agent-disabled"); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            throw new SQLException("Not a wrapper for " + iface.getName());
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
    }

}
