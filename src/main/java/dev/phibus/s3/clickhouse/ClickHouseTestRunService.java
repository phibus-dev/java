package dev.phibus.s3.clickhouse;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ClickHouseTestRunService {
    private static final Logger LOG = LoggerFactory.getLogger(ClickHouseTestRunService.class);

    private final Map<UUID, ClickHouseTestRun> runs = new ConcurrentHashMap<>();
    private final ClickHouseLoadTestEngine engine;
    private final ClickHouseConnectionProvider connections;
    private final ObjectProvider<ClickHouseHistoryStore> history;
    private final Executor testExecutor;

    public ClickHouseTestRunService(ClickHouseLoadTestEngine engine,
                                    ClickHouseConnectionProvider connections,
                                    ObjectProvider<ClickHouseHistoryStore> history,
                                    @Qualifier("testExecutor") Executor testExecutor) {
        this.engine = engine;
        this.connections = connections;
        this.history = history;
        this.testExecutor = testExecutor;
    }

    public ClickHouseTestRun create(ClickHouseTestRequest request) {
        validate(request);
        String endpoint = connections.endpoint(request.profileId(), request.endpoint());
        ClickHouseTestRun run = new ClickHouseTestRun(request, endpoint);
        ClickHouseHistoryStore store = history.getIfAvailable();
        if (store != null) {
            try {
                store.save(run.snapshot(), request);
            } catch (RuntimeException e) {
                LOG.error("Cannot create ClickHouse test history for run {}", run.id(), e);
                throw new IllegalStateException("Cannot create ClickHouse test history; test was not started", e);
            }
        }
        runs.put(run.id(), run);
        testExecutor.execute(() -> {
            engine.execute(run);
            if (store != null) {
                try { store.save(run.snapshot(), request); }
                catch (RuntimeException e) { LOG.error("Cannot persist ClickHouse test history for run {}", run.id(), e); }
            }
        });
        return run;
    }

    public ClickHouseTestRun createDistributed(ClickHouseTestRequest request, ClickHouseConnectionSpec connectionSpec) {
        validate(request);
        if (connectionSpec == null) throw new IllegalArgumentException("ClickHouse connection spec is required");
        ClickHouseTestRun run = new ClickHouseTestRun(request, connectionSpec.endpoint());
        runs.put(run.id(), run);
        testExecutor.execute(() -> engine.execute(run, connectionSpec));
        return run;
    }

    public ClickHouseTestRun get(UUID id) {
        ClickHouseTestRun run = runs.get(id);
        if (run == null) throw new IllegalArgumentException("ClickHouse test not found: " + id);
        return run;
    }

    public List<ClickHouseTestRun.Snapshot> list() {
        return runs.values().stream().map(ClickHouseTestRun::snapshot)
                .sorted(Comparator.comparing(ClickHouseTestRun.Snapshot::createdAt).reversed()).toList();
    }

    public void cancel(UUID id) { get(id).cancel(); }

    static void validate(ClickHouseTestRequest request) {
        if (request == null) throw new IllegalArgumentException("ClickHouse test request is required");
        if (request.profileId() == null) throw new IllegalArgumentException("ClickHouse profile is required");
        String table = request.normalizedTable();
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid ClickHouse table name");
        if (!List.of("INSERT", "SELECT", "INSERT_SELECT").contains(request.normalizedOperation()))
            throw new IllegalArgumentException("Unsupported ClickHouse operation: " + request.operation());
        if (request.concurrency() < 1 || request.concurrency() > 64)
            throw new IllegalArgumentException("Concurrency must be 1..64");
        if (request.batchSize() < 1 || request.batchSize() > 100000)
            throw new IllegalArgumentException("Batch size must be 1..100000");
        if (request.rowCount() < 1) throw new IllegalArgumentException("Row count must be positive");
        if (request.durationSeconds() < 0 || request.durationSeconds() > 86400)
            throw new IllegalArgumentException("Duration must be 0..86400 seconds");
        if (request.warmupSeconds() < 0 || request.warmupSeconds() > 3600)
            throw new IllegalArgumentException("Warm-up must be 0..3600 seconds");
        if (request.payloadBytes() < 1 || request.payloadBytes() > 1048576)
            throw new IllegalArgumentException("Payload size must be 1..1048576 bytes");
    }
}
