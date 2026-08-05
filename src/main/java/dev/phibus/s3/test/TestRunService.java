package dev.phibus.s3.test;

import dev.phibus.s3.history.HistoryRequestMetadataUpdater;
import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.settings.S3ProfileService;
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
    private final HistoryRequestMetadataUpdater metadataUpdater;
    private final S3ProfileService profileService;

    public TestRunService(UploadTestEngine engine, @Qualifier("testExecutor") Executor testExecutor,
                          TestHistoryStore historyStore, HistoryRequestMetadataUpdater metadataUpdater,
                          S3ProfileService profileService) {
        this.engine = engine;
        this.testExecutor = testExecutor;
        this.historyStore = historyStore;
        this.metadataUpdater = metadataUpdater;
        this.profileService = profileService;
    }

    public TestRun create(TestRequest request) {
        TestRequest effectiveRequest = resolveProfile(request);
        TestRun run = new TestRun(effectiveRequest);
        runs.put(run.id(), run);
        testExecutor.execute(() -> executeAndPersist(run));
        return run;
    }

    private TestRequest resolveProfile(TestRequest request) {
        S3ProfileService.Profile profile = request.profileId() == null
                ? profileService.defaultProfile() : profileService.get(request.profileId());
        if (profile == null) return request;
        String bucket = profile.bucket() == null || profile.bucket().isBlank() ? request.bucket() : profile.bucket();
        if (bucket == null || bucket.isBlank())
            throw new IllegalArgumentException("Bucket is not configured in selected S3 profile or request");
        UUID effectiveProfileId = request.profileId() == null ? profile.id() : request.profileId();
        TestRequest profiled = new TestRequest(request.endpoint(), request.bucket(), request.region(), request.accessKey(),
                request.secretKey(), request.pathStyleAccess(), request.objectKey(), request.objectSizeMiB(),
                request.partSizeMiB(), request.parallelism(), request.objectCount(), request.deleteAfterTest(),
                request.operation(), request.executionMode(), request.durationSeconds(), request.warmupSeconds(),
                request.workloadProfile(), request.workloadWeights(), request.targetOperationsPerSecond(),
                request.operationThreads(), effectiveProfileId);
        return profiled.withConnection(profile.endpoint(), bucket, profile.region(), profile.pathStyleAccess());
    }

    private void executeAndPersist(TestRun run) {
        try {
            engine.execute(run);
        } finally {
            historyStore.save(run.snapshot());
            metadataUpdater.update(run.id(), run.request());
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

    public void cancel(UUID id) { get(id).cancel(); }
    public List<String> listBuckets(TestRequest request) { return engine.listBuckets(resolveProfile(request)); }

    public static final class TestNotFoundException extends RuntimeException {
        public TestNotFoundException(UUID id) { super("Test not found: " + id); }
    }
}
