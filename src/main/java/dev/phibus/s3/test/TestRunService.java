package dev.phibus.s3.test;

import dev.phibus.s3.distributed.ApplicationMode;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TestRunService {
    private final Map<UUID, TestRun> runs = new ConcurrentHashMap<>();
    private final LoadTestEngineRegistry engineRegistry;
    private final S3LoadTestEngine s3Engine;
    private final Executor testExecutor;
    private final TestHistoryStore historyStore;
    private final HistoryRequestMetadataUpdater metadataUpdater;
    private final S3ProfileService profileService;
    private final ApplicationMode applicationMode;

    public TestRunService(LoadTestEngineRegistry engineRegistry, S3LoadTestEngine s3Engine,
                          @Qualifier("testExecutor") Executor testExecutor,
                          TestHistoryStore historyStore, HistoryRequestMetadataUpdater metadataUpdater,
                          S3ProfileService profileService,
                          @Value("${s3perf.application-mode:COORDINATOR}") String applicationMode) {
        this.engineRegistry = engineRegistry;
        this.s3Engine = s3Engine;
        this.testExecutor = testExecutor;
        this.historyStore = historyStore;
        this.metadataUpdater = metadataUpdater;
        this.profileService = profileService;
        this.applicationMode = ApplicationMode.from(applicationMode);
    }

    /**
     * Backwards-compatible S3 entry point. New workload types will select their
     * own TestType while existing API/UI calls remain unchanged.
     */
    public TestRun create(TestRequest request) {
        return create(TestType.S3, request);
    }

    public TestRun create(TestType testType, TestRequest request) {
        if (testType == null) throw new IllegalArgumentException("Test type is required");
        // S3 profiles remain an S3-specific concern. Distributed assignments already contain
        // effective connection settings, so AGENT mode never resolves profiles from PostgreSQL.
        TestRequest effectiveRequest = testType == TestType.S3 && applicationMode != ApplicationMode.AGENT
                ? resolveProfile(request) : request;
        TestRun run = new TestRun(effectiveRequest);
        runs.put(run.id(), run);
        testExecutor.execute(() -> execute(testType, run));
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

    private void execute(TestType testType, TestRun run) {
        try {
            engineRegistry.require(testType).execute(run);
        } finally {
            if (applicationMode != ApplicationMode.AGENT) {
                historyStore.save(run.snapshot());
                metadataUpdater.update(run.id(), run.request());
            }
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

    public List<String> listBuckets(TestRequest request) {
        TestRequest effectiveRequest = applicationMode == ApplicationMode.AGENT ? request : resolveProfile(request);
        return s3Engine.listBuckets(effectiveRequest);
    }

    public List<TestType> supportedTestTypes() {
        return engineRegistry.supportedTypes();
    }

    public static final class TestNotFoundException extends RuntimeException {
        public TestNotFoundException(UUID id) { super("Test not found: " + id); }
    }
}
