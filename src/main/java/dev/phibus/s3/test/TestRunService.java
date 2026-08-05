package dev.phibus.s3.test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class TestRunService {
    private final Map<UUID, TestRun> runs = new ConcurrentHashMap<>();
    private final UploadTestEngine engine;

    public TestRunService(UploadTestEngine engine) {
        this.engine = engine;
    }

    public TestRun create(TestRequest request) {
        TestRun run = new TestRun(request);
        runs.put(run.id(), run);
        execute(run);
        return run;
    }

    @Async("testExecutor")
    public void execute(TestRun run) {
        engine.execute(run);
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
