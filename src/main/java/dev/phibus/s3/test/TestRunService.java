package dev.phibus.s3.test;

import dev.phibus.s3.history.TestHistoryStore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class TestRunService {
    private final Map<UUID, TestRun> runs = new ConcurrentHashMap<>();
    private final UploadTestEngine engine;
    private final Executor testExecutor;
    private final TestHistoryStore historyStore;

    public TestRunService(UploadTestEngine engine, @Qualifier("testExecutor") Executor testExecutor,
                          TestHistoryStore historyStore) {
        this.engine = engine;
        this.testExecutor = testExecutor;
        this.historyStore = historyStore;
    }

    public TestRun create(TestRequest request) {
        TestRun run = new TestRun(request);
        runs.put(run.id(), run);
        testExecutor.execute(() -> executeAndPersist(run));
        return run;
    }

    private void executeAndPersist(TestRun run) {
        try {
            engine.execute(run);
        } finally {
            historyStore.save(run.snapshot());
        }
    }

    public TestRun get(UUID id) {
        TestRun run = runs.get(id);
        if (run == null) throw new TestNotFoundException(id);
        return run;
    }

    public List<TestRun.Snapshot> list() {
        return runs.values().stream().map(TestRun::snapshot)
                .sorted(Comparator.comparing(TestRun.Snapshot::createdAt).reversed()).toList();
    }

    public void cancel(UUID id) {
        get(id).cancel();
    }

    public List<String> listBuckets(TestRequest request) {
        return engine.listBuckets(request);
    }

    public static final class TestNotFoundException extends RuntimeException {
        public TestNotFoundException(UUID id) { super("Test not found: " + id); }
    }
}
