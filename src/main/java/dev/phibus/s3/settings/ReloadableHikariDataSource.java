package dev.phibus.s3.settings;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** A lazily-created Hikari pool that is atomically replaced after bootstrap settings change. */
public final class ReloadableHikariDataSource implements DataSource, AutoCloseable {
    private final SettingsService settingsService;
    private final BootstrapSecretCodec codec;
    private final MeterRegistry meterRegistry;
    private final int maximumPoolSize;
    private final int minimumIdle;
    private final long connectionTimeoutMs;
    private final long validationTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    private final long leakDetectionMs;
    private final AtomicLong generation = new AtomicLong();
    private volatile PoolHolder current;

    public ReloadableHikariDataSource(SettingsService settingsService, BootstrapSecretCodec codec,
                                      MeterRegistry meterRegistry, int maximumPoolSize, int minimumIdle,
                                      long connectionTimeoutMs, long validationTimeoutMs, long idleTimeoutMs,
                                      long maxLifetimeMs, long leakDetectionMs) {
        this.settingsService = settingsService;
        this.codec = codec;
        this.meterRegistry = meterRegistry;
        this.maximumPoolSize = Math.max(1, maximumPoolSize);
        this.minimumIdle = Math.max(0, Math.min(minimumIdle, this.maximumPoolSize));
        this.connectionTimeoutMs = Math.max(250, connectionTimeoutMs);
        this.validationTimeoutMs = Math.max(250, Math.min(validationTimeoutMs, this.connectionTimeoutMs));
        this.idleTimeoutMs = Math.max(10_000, idleTimeoutMs);
        this.maxLifetimeMs = Math.max(30_000, maxLifetimeMs);
        this.leakDetectionMs = leakDetectionMs <= 0 ? 0 : Math.max(2_000, leakDetectionMs);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return pool().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return pool().getConnection(username, password);
    }

    private HikariDataSource pool() throws SQLException {
        PoolKey wanted = configuredKey();
        PoolHolder existing = current;
        if (existing != null && existing.key().equals(wanted) && !existing.pool().isClosed()) return existing.pool();
        synchronized (this) {
            existing = current;
            if (existing != null && existing.key().equals(wanted) && !existing.pool().isClosed()) return existing.pool();
            HikariDataSource replacement = createPool(wanted);
            current = new PoolHolder(wanted, replacement);
            if (existing != null) existing.pool().close();
            return replacement;
        }
    }

    private PoolKey configuredKey() throws SQLException {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        if (!settings.configured()) {
            throw new SQLException("PostgreSQL is not configured; open /settings and save connection settings");
        }
        return new PoolKey(settings.jdbcUrl().trim(), settings.username().trim(),
                Objects.toString(settings.encryptedPassword(), ""));
    }

    private HikariDataSource createPool(PoolKey key) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("s3perf-postgresql-" + generation.incrementAndGet());
        config.setJdbcUrl(key.jdbcUrl());
        config.setUsername(key.username());
        config.setPassword(codec.decrypt(key.encryptedPassword()));
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setValidationTimeout(validationTimeoutMs);
        config.setIdleTimeout(idleTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        config.setLeakDetectionThreshold(leakDetectionMs);
        config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        return new HikariDataSource(config);
    }

    public synchronized String poolName() {
        return current == null ? null : current.pool().getPoolName();
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return pool().getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { pool().setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { pool().setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return pool().getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        try {
            return pool().getParentLogger();
        } catch (SQLException error) {
            throw new SQLFeatureNotSupportedException(error);
        }
    }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        return pool().unwrap(iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || pool().isWrapperFor(iface);
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        PoolHolder existing = current;
        current = null;
        if (existing != null) existing.pool().close();
    }

    private record PoolKey(String jdbcUrl, String username, String encryptedPassword) { }
    private record PoolHolder(PoolKey key, HikariDataSource pool) { }
}
