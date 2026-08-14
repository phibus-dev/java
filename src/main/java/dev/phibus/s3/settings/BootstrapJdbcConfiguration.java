package dev.phibus.s3.settings;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class BootstrapJdbcConfiguration {

    @Bean
    DataSource bootstrapDataSource(SettingsService settingsService, BootstrapSecretCodec codec) {
        return new BootstrapDataSource(settingsService, codec);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource bootstrapDataSource) {
        return new JdbcTemplate(bootstrapDataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource bootstrapDataSource) {
        return new DataSourceTransactionManager(bootstrapDataSource);
    }

    @Bean
    ApplicationRunner migrateConfiguredPostgreSql(SettingsService settingsService,
                                                   BootstrapSecretCodec codec,
                                                   DataSource bootstrapDataSource) {
        return arguments -> {
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

    private static final class BootstrapDataSource implements DataSource {
        private final SettingsService settingsService;
        private final BootstrapSecretCodec codec;

        private BootstrapDataSource(SettingsService settingsService, BootstrapSecretCodec codec) {
            this.settingsService = settingsService;
            this.codec = codec;
        }

        @Override
        public Connection getConnection() throws SQLException {
            BootstrapSettings.PostgreSqlSettings postgresql = configuredSettings();
            return DriverManager.getConnection(postgresql.jdbcUrl(), postgresql.username(),
                    codec.decrypt(postgresql.encryptedPassword()));
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            BootstrapSettings.PostgreSqlSettings postgresql = configuredSettings();
            return DriverManager.getConnection(postgresql.jdbcUrl(), username, password);
        }

        private BootstrapSettings.PostgreSqlSettings configuredSettings() throws SQLException {
            BootstrapSettings.PostgreSqlSettings postgresql = settingsService.load().postgresql();
            if (!postgresql.configured()) {
                throw new SQLException("PostgreSQL is not configured; open /settings and restart the application");
            }
            return postgresql;
        }

        @Override
        public PrintWriter getLogWriter() {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getLogger("dev.phibus.s3.jdbc");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Not a wrapper for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
