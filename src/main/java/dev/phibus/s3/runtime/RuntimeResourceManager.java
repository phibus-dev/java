package dev.phibus.s3.runtime;

import dev.phibus.s3.distributed.ApplicationMode;
import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.test.TestType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Admission control for local and agent-side load generators.
 *
 * <p>Every run must fit the global, engine and execution-scope quotas. Worker
 * reservations use the same hierarchy and are rolled back atomically when a
 * lower-level quota cannot be acquired. Limits are fail-fast: an overloaded
 * node rejects work instead of growing an unbounded queue.</p>
 */
@Component
public final class RuntimeResourceManager implements AutoCloseable {
    private final Limits limits;
    private final RunScope nodeScope;
    private final Duration maxRunDuration;
    private final Semaphore runSlots;
    private final Semaphore workerSlots;
    private final Map<TestType, Semaphore> runSlotsByType;
    private final Map<RunScope, Semaphore> runSlotsByScope;
    private final Map<TestType, Semaphore> workerSlotsByType;
    private final Map<RunScope, Semaphore> workerSlotsByScope;
    private final AtomicInteger activeRuns = new AtomicInteger();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final Map<TestType, AtomicInteger> activeRunsByType = counters(TestType.class);
    private final Map<RunScope, AtomicInteger> activeRunsByScope = counters(RunScope.class);
    private final Map<TestType, AtomicInteger> activeWorkersByType = counters(TestType.class);
    private final Map<RunScope, AtomicInteger> activeWorkersByScope = counters(RunScope.class);
    private final Map<UUID, AtomicInteger> activeWorkersByRun = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlineScheduler;
    private final MeterRegistry registry;
    private final Counter rejectedRuns;
    private final Counter rejectedWorkers;
    private final Counter expiredRuns;

    @Autowired
    public RuntimeResourceManager(
            @Value("${s3perf.runtime.max-concurrent-runs:8}") int maxConcurrentRuns,
            @Value("${s3perf.runtime.max-worker-tasks:64}") int maxWorkerTasks,
            @Value("${s3perf.runtime.max-worker-tasks-per-run:${s3perf.runtime.max-worker-tasks:64}}")
                    int maxWorkerTasksPerRun,
            @Value("${s3perf.runtime.max-run-duration-seconds:86400}") long maxRunDurationSeconds,
            @Value("${s3perf.runtime.max-concurrent-runs-s3:${s3perf.runtime.max-concurrent-runs:8}}") int maxS3Runs,
            @Value("${s3perf.runtime.max-concurrent-runs-clickhouse:${s3perf.runtime.max-concurrent-runs:8}}")
                    int maxClickHouseRuns,
            @Value("${s3perf.runtime.max-concurrent-runs-kafka:${s3perf.runtime.max-concurrent-runs:8}}")
                    int maxKafkaRuns,
            @Value("${s3perf.runtime.max-concurrent-runs-local:${s3perf.runtime.max-concurrent-runs:8}}") int maxLocalRuns,
            @Value("${s3perf.runtime.max-concurrent-runs-distributed:${s3perf.runtime.max-concurrent-runs:8}}")
                    int maxDistributedRuns,
            @Value("${s3perf.runtime.max-worker-tasks-s3:${s3perf.runtime.max-worker-tasks:64}}") int maxS3Workers,
            @Value("${s3perf.runtime.max-worker-tasks-clickhouse:${s3perf.runtime.max-worker-tasks:64}}")
                    int maxClickHouseWorkers,
            @Value("${s3perf.runtime.max-worker-tasks-kafka:${s3perf.runtime.max-worker-tasks:64}}")
                    int maxKafkaWorkers,
            @Value("${s3perf.runtime.max-worker-tasks-local:${s3perf.runtime.max-worker-tasks:64}}") int maxLocalWorkers,
            @Value("${s3perf.runtime.max-worker-tasks-distributed:${s3perf.runtime.max-worker-tasks:64}}")
                    int maxDistributedWorkers,
            @Value("${s3perf.runtime.max-s3-bytes-per-run:2251799813685248}") long maxS3BytesPerRun,
            @Value("${s3perf.runtime.max-clickhouse-rows-per-run:9223372036854775807}") long maxClickHouseRowsPerRun,
            @Value("${s3perf.runtime.max-clickhouse-queries-per-run:10000000}") long maxClickHouseQueriesPerRun,
            @Value("${s3perf.runtime.max-kafka-messages-per-run:9223372036854775807}") long maxKafkaMessagesPerRun,
            @Value("${s3perf.application-mode:COORDINATOR}") String applicationMode,
            MeterRegistry registry) {
        this(new Limits(maxConcurrentRuns, maxWorkerTasks, maxWorkerTasksPerRun, maxRunDurationSeconds,
                        Map.of(TestType.S3, maxS3Runs, TestType.CLICKHOUSE, maxClickHouseRuns,
                                TestType.KAFKA, maxKafkaRuns),
                        Map.of(RunScope.LOCAL, maxLocalRuns, RunScope.DISTRIBUTED, maxDistributedRuns),
                        Map.of(TestType.S3, maxS3Workers, TestType.CLICKHOUSE, maxClickHouseWorkers,
                                TestType.KAFKA, maxKafkaWorkers),
                        Map.of(RunScope.LOCAL, maxLocalWorkers,
                                RunScope.DISTRIBUTED, maxDistributedWorkers),
                        maxS3BytesPerRun, maxClickHouseRowsPerRun, maxClickHouseQueriesPerRun,
                        maxKafkaMessagesPerRun),
                ApplicationMode.from(applicationMode) == ApplicationMode.AGENT
                        ? RunScope.DISTRIBUTED : RunScope.LOCAL,
                registry);
    }

    /** Backwards-compatible constructor for focused engine tests. */
    public RuntimeResourceManager(int maxConcurrentRuns, int maxWorkerTasks,
                                  long maxRunDurationSeconds, MeterRegistry registry) {
        this(Limits.compatible(maxConcurrentRuns, maxWorkerTasks, maxRunDurationSeconds),
                RunScope.LOCAL, registry);
    }

    RuntimeResourceManager(Limits suppliedLimits, RunScope nodeScope, MeterRegistry registry) {
        this.limits = Objects.requireNonNull(suppliedLimits, "Runtime limits are required").normalized();
        this.nodeScope = Objects.requireNonNull(nodeScope, "Runtime scope is required");
        this.registry = Objects.requireNonNull(registry, "Meter registry is required");
        this.maxRunDuration = Duration.ofSeconds(this.limits.maxRunDurationSeconds());
        this.runSlots = new Semaphore(this.limits.maxConcurrentRuns(), true);
        this.workerSlots = new Semaphore(this.limits.maxWorkerTasks(), true);
        this.runSlotsByType = semaphores(TestType.class, this.limits.maxRunsByType());
        this.runSlotsByScope = semaphores(RunScope.class, this.limits.maxRunsByScope());
        this.workerSlotsByType = semaphores(TestType.class, this.limits.maxWorkersByType());
        this.workerSlotsByScope = semaphores(RunScope.class, this.limits.maxWorkersByScope());
        this.deadlineScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("run-deadline-", 0).factory());
        this.rejectedRuns = registry.counter("s3perf.runtime.rejections", "resource", "run");
        this.rejectedWorkers = registry.counter("s3perf.runtime.rejections", "resource", "worker");
        this.expiredRuns = registry.counter("s3perf.runtime.deadlines.exceeded");
        registerMetrics();
    }

    public void validateDemand(RunDemand demand) {
        Objects.requireNonNull(demand, "Runtime demand is required").validated();
        TestType type = demand.testType();
        if (demand.workerTasks() > limits.maxWorkerTasksPerRun()) {
            rejectDemand(type, "workers_per_run", demand.workerTasks(), limits.maxWorkerTasksPerRun(), "worker tasks");
        }
        if (demand.workerTasks() > limits.maxWorkersByType().get(type)) {
            rejectDemand(type, "workers_by_type", demand.workerTasks(), limits.maxWorkersByType().get(type),
                    "worker tasks");
        }
        if (demand.workerTasks() > limits.maxWorkersByScope().get(nodeScope)) {
            rejectDemand(type, "workers_by_scope", demand.workerTasks(), limits.maxWorkersByScope().get(nodeScope),
                    "worker tasks");
        }
        if (demand.durationSeconds() > limits.maxRunDurationSeconds()) {
            rejectDemand(type, "duration", demand.durationSeconds(), limits.maxRunDurationSeconds(), "seconds");
        }
        if (type == TestType.S3 && demand.bytes() > limits.maxS3BytesPerRun()) {
            rejectDemand(type, "s3_bytes", demand.bytes(), limits.maxS3BytesPerRun(), "bytes");
        }
        if (type == TestType.CLICKHOUSE && demand.rows() > limits.maxClickHouseRowsPerRun()) {
            rejectDemand(type, "clickhouse_rows", demand.rows(), limits.maxClickHouseRowsPerRun(), "rows");
        }
        if (type == TestType.CLICKHOUSE && demand.queries() > limits.maxClickHouseQueriesPerRun()) {
            rejectDemand(type, "clickhouse_queries", demand.queries(), limits.maxClickHouseQueriesPerRun(), "queries");
        }
        if (type == TestType.KAFKA && demand.messages() > limits.maxKafkaMessagesPerRun()) {
            rejectDemand(type, "kafka_messages", demand.messages(), limits.maxKafkaMessagesPerRun(), "messages");
        }
    }

    public Future<?> submit(Executor executor, String workload, UUID runId,
                            Runnable deadlineAction, Runnable task) {
        TestType type = typeOf(workload);
        RunPermit permit = acquireRun(type, workload, runId);
        FutureTask<Void> future = new FutureTask<>(() -> {
            try (permit) {
                task.run();
            }
            return null;
        });
        permit.armDeadline(() -> {
            expiredRuns.increment();
            try {
                deadlineAction.run();
            } finally {
                future.cancel(true);
                permit.close();
            }
        });
        try {
            executor.execute(future);
            return future;
        } catch (RuntimeException error) {
            permit.close();
            throw error;
        }
    }

    public WorkerPermit acquireWorkers(int requested, String workload, UUID runId) {
        TestType type = typeOf(workload);
        Objects.requireNonNull(runId, "Run id is required");
        int permits = Math.max(1, requested);
        if (!reserveRunWorkers(runId, permits)) {
            throw workerRejection(type, "per_run", permits, limits.maxWorkerTasksPerRun(), runId, workload);
        }
        if (!workerSlots.tryAcquire(permits)) {
            releaseRunWorkers(runId, permits);
            throw workerRejection(type, "global", permits, limits.maxWorkerTasks(), runId, workload);
        }
        Semaphore typeSlots = workerSlotsByType.get(type);
        if (!typeSlots.tryAcquire(permits)) {
            workerSlots.release(permits);
            releaseRunWorkers(runId, permits);
            throw workerRejection(type, "test_type", permits, limits.maxWorkersByType().get(type), runId, workload);
        }
        Semaphore scopeSlots = workerSlotsByScope.get(nodeScope);
        if (!scopeSlots.tryAcquire(permits)) {
            typeSlots.release(permits);
            workerSlots.release(permits);
            releaseRunWorkers(runId, permits);
            throw workerRejection(type, "scope", permits, limits.maxWorkersByScope().get(nodeScope), runId, workload);
        }
        activeWorkers.addAndGet(permits);
        activeWorkersByType.get(type).addAndGet(permits);
        activeWorkersByScope.get(nodeScope).addAndGet(permits);
        return new WorkerPermit(type, nodeScope, runId, permits);
    }

    public Map<String, String> telemetryTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("runtime.maxRuns", Integer.toString(limits.maxConcurrentRuns()));
        tags.put("runtime.activeRuns", Integer.toString(activeRuns.get()));
        tags.put("runtime.availableRuns", Integer.toString(runSlots.availablePermits()));
        tags.put("runtime.maxWorkers", Integer.toString(limits.maxWorkerTasks()));
        tags.put("runtime.maxWorkersPerRun", Integer.toString(limits.maxWorkerTasksPerRun()));
        tags.put("runtime.maxRunDurationSeconds", Long.toString(limits.maxRunDurationSeconds()));
        tags.put("runtime.activeWorkers", Integer.toString(activeWorkers.get()));
        tags.put("runtime.availableWorkers", Integer.toString(workerSlots.availablePermits()));
        for (TestType type : TestType.values()) {
            tags.put("runtime." + type + ".maxRuns", Integer.toString(limits.maxRunsByType().get(type)));
            tags.put("runtime." + type + ".activeRuns", Integer.toString(activeRunsByType.get(type).get()));
            tags.put("runtime." + type + ".maxWorkers", Integer.toString(limits.maxWorkersByType().get(type)));
            tags.put("runtime." + type + ".activeWorkers", Integer.toString(activeWorkersByType.get(type).get()));
        }
        tags.put("runtime.scope", nodeScope.name());
        tags.put("runtime.scope.maxRuns", Integer.toString(limits.maxRunsByScope().get(nodeScope)));
        tags.put("runtime.scope.activeRuns", Integer.toString(activeRunsByScope.get(nodeScope).get()));
        tags.put("runtime.scope.maxWorkers", Integer.toString(limits.maxWorkersByScope().get(nodeScope)));
        tags.put("runtime.scope.activeWorkers", Integer.toString(activeWorkersByScope.get(nodeScope).get()));
        return Map.copyOf(tags);
    }

    public CapacitySnapshot capacity() {
        Map<TestType, Capacity> byType = new EnumMap<>(TestType.class);
        for (TestType type : TestType.values()) {
            byType.put(type, new Capacity(limits.maxRunsByType().get(type), activeRunsByType.get(type).get(),
                    runSlotsByType.get(type).availablePermits(), limits.maxWorkersByType().get(type),
                    activeWorkersByType.get(type).get(), workerSlotsByType.get(type).availablePermits()));
        }
        Map<RunScope, Capacity> byScope = new EnumMap<>(RunScope.class);
        for (RunScope scope : RunScope.values()) {
            byScope.put(scope, new Capacity(limits.maxRunsByScope().get(scope), activeRunsByScope.get(scope).get(),
                    runSlotsByScope.get(scope).availablePermits(), limits.maxWorkersByScope().get(scope),
                    activeWorkersByScope.get(scope).get(), workerSlotsByScope.get(scope).availablePermits()));
        }
        return new CapacitySnapshot(nodeScope, limits.maxConcurrentRuns(), activeRuns.get(), runSlots.availablePermits(),
                limits.maxWorkerTasks(), activeWorkers.get(), workerSlots.availablePermits(),
                limits.maxWorkerTasksPerRun(), limits.maxRunDurationSeconds(),
                Collections.unmodifiableMap(byType), Collections.unmodifiableMap(byScope));
    }

    int activeRuns() { return activeRuns.get(); }
    int activeWorkers() { return activeWorkers.get(); }

    private RunPermit acquireRun(TestType type, String workload, UUID runId) {
        if (!runSlots.tryAcquire()) {
            throw runRejection(type, "global", limits.maxConcurrentRuns(), runId, workload);
        }
        Semaphore typeSlots = runSlotsByType.get(type);
        if (!typeSlots.tryAcquire()) {
            runSlots.release();
            throw runRejection(type, "test_type", limits.maxRunsByType().get(type), runId, workload);
        }
        Semaphore scopeSlots = runSlotsByScope.get(nodeScope);
        if (!scopeSlots.tryAcquire()) {
            typeSlots.release();
            runSlots.release();
            throw runRejection(type, "scope", limits.maxRunsByScope().get(nodeScope), runId, workload);
        }
        activeRuns.incrementAndGet();
        activeRunsByType.get(type).incrementAndGet();
        activeRunsByScope.get(nodeScope).incrementAndGet();
        return new RunPermit(type, nodeScope);
    }

    private RunCapacityExceededException runRejection(TestType type, String constraint, int limit,
                                                       UUID runId, String workload) {
        rejectedRuns.increment();
        limitRejection("run", constraint, type).increment();
        return new RunCapacityExceededException("Run capacity exceeded for " + workload + " run " + runId
                + ": constraint=" + constraint + ", testType=" + type + ", scope=" + nodeScope
                + ", limit=" + limit);
    }

    private RunCapacityExceededException workerRejection(TestType type, String constraint, int requested,
                                                          int limit, UUID runId, String workload) {
        rejectedWorkers.increment();
        limitRejection("worker", constraint, type).increment();
        return new RunCapacityExceededException("Worker capacity exceeded for " + workload + " run " + runId
                + ": constraint=" + constraint + ", testType=" + type + ", scope=" + nodeScope
                + ", requested=" + requested + ", limit=" + limit);
    }

    private void rejectDemand(TestType type, String constraint, long requested, long limit, String unit) {
        limitRejection("request", constraint, type).increment();
        throw new RunLimitExceededException("Runtime limit exceeded for " + type + " / " + nodeScope
                + ": constraint=" + constraint + ", requested=" + requested + " " + unit
                + ", limit=" + limit + " " + unit);
    }

    private Counter limitRejection(String resource, String constraint, TestType type) {
        return registry.counter("s3perf.runtime.limit.rejections", "resource", resource,
                "constraint", constraint, "test_type", type.name().toLowerCase(Locale.ROOT),
                "scope", nodeScope.name().toLowerCase(Locale.ROOT));
    }

    private boolean reserveRunWorkers(UUID runId, int permits) {
        AtomicBoolean reserved = new AtomicBoolean();
        activeWorkersByRun.compute(runId, (ignored, current) -> {
            int active = current == null ? 0 : current.get();
            if (permits > limits.maxWorkerTasksPerRun() - active) return current;
            AtomicInteger result = current == null ? new AtomicInteger() : current;
            result.addAndGet(permits);
            reserved.set(true);
            return result;
        });
        return reserved.get();
    }

    private void releaseRunWorkers(UUID runId, int permits) {
        activeWorkersByRun.computeIfPresent(runId, (ignored, current) ->
                current.addAndGet(-permits) == 0 ? null : current);
    }

    private void registerMetrics() {
        registry.gauge("s3perf.runtime.runs.active", activeRuns);
        registry.gauge("s3perf.runtime.workers.active", activeWorkers);
        registry.gauge("s3perf.runtime.runs.available", runSlots, Semaphore::availablePermits);
        registry.gauge("s3perf.runtime.workers.available", workerSlots, Semaphore::availablePermits);
        for (TestType type : TestType.values()) {
            String tag = type.name().toLowerCase(Locale.ROOT);
            Gauge.builder("s3perf.runtime.type.runs.active", activeRunsByType.get(type), AtomicInteger::get)
                    .tag("test_type", tag).register(registry);
            Gauge.builder("s3perf.runtime.type.runs.available", runSlotsByType.get(type), Semaphore::availablePermits)
                    .tag("test_type", tag).register(registry);
            Gauge.builder("s3perf.runtime.type.workers.active", activeWorkersByType.get(type), AtomicInteger::get)
                    .tag("test_type", tag).register(registry);
            Gauge.builder("s3perf.runtime.type.workers.available", workerSlotsByType.get(type), Semaphore::availablePermits)
                    .tag("test_type", tag).register(registry);
        }
        for (RunScope scope : RunScope.values()) {
            String tag = scope.name().toLowerCase(Locale.ROOT);
            Gauge.builder("s3perf.runtime.scope.runs.active", activeRunsByScope.get(scope), AtomicInteger::get)
                    .tag("scope", tag).register(registry);
            Gauge.builder("s3perf.runtime.scope.runs.available", runSlotsByScope.get(scope), Semaphore::availablePermits)
                    .tag("scope", tag).register(registry);
            Gauge.builder("s3perf.runtime.scope.workers.active", activeWorkersByScope.get(scope), AtomicInteger::get)
                    .tag("scope", tag).register(registry);
            Gauge.builder("s3perf.runtime.scope.workers.available", workerSlotsByScope.get(scope), Semaphore::availablePermits)
                    .tag("scope", tag).register(registry);
        }
    }

    private static TestType typeOf(String workload) {
        String normalized = workload == null ? "" : workload.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("S3")) return TestType.S3;
        if (normalized.startsWith("CLICKHOUSE")) return TestType.CLICKHOUSE;
        if (normalized.startsWith("KAFKA")) return TestType.KAFKA;
        throw new IllegalArgumentException("Unsupported runtime workload: " + workload);
    }

    @PreDestroy
    @Override
    public void close() {
        deadlineScheduler.shutdownNow();
    }

    private final class RunPermit implements AutoCloseable {
        private final TestType type;
        private final RunScope scope;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> deadline;

        private RunPermit(TestType type, RunScope scope) {
            this.type = type;
            this.scope = scope;
        }

        synchronized void armDeadline(Runnable action) {
            if (closed.get()) return;
            deadline = deadlineScheduler.schedule(action, maxRunDuration.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            ScheduledFuture<?> scheduled = deadline;
            if (scheduled != null) scheduled.cancel(false);
            activeRuns.decrementAndGet();
            activeRunsByType.get(type).decrementAndGet();
            activeRunsByScope.get(scope).decrementAndGet();
            runSlotsByScope.get(scope).release();
            runSlotsByType.get(type).release();
            runSlots.release();
        }
    }

    public final class WorkerPermit implements AutoCloseable {
        private final TestType type;
        private final RunScope scope;
        private final UUID runId;
        private final int permits;
        private final AtomicBoolean closed = new AtomicBoolean();

        private WorkerPermit(TestType type, RunScope scope, UUID runId, int permits) {
            this.type = type;
            this.scope = scope;
            this.runId = runId;
            this.permits = permits;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            activeWorkers.addAndGet(-permits);
            activeWorkersByType.get(type).addAndGet(-permits);
            activeWorkersByScope.get(scope).addAndGet(-permits);
            workerSlotsByScope.get(scope).release(permits);
            workerSlotsByType.get(type).release(permits);
            workerSlots.release(permits);
            releaseRunWorkers(runId, permits);
        }
    }

    public record RunDemand(TestType testType, int workerTasks, long durationSeconds,
                            long bytes, long rows, long queries, long messages) {
        public static RunDemand s3(int workers, long durationSeconds, long bytes) {
            return new RunDemand(TestType.S3, workers, durationSeconds, bytes, 0, 0, 0);
        }

        public static RunDemand clickHouse(int workers, long durationSeconds, long rows, long queries) {
            return new RunDemand(TestType.CLICKHOUSE, workers, durationSeconds, 0, rows, queries, 0);
        }

        public static RunDemand kafka(int workers, long messages) {
            return new RunDemand(TestType.KAFKA, workers, 0, 0, 0, 0, messages);
        }

        RunDemand validated() {
            Objects.requireNonNull(testType, "Test type is required");
            if (workerTasks < 1 || durationSeconds < 0 || bytes < 0 || rows < 0 || queries < 0 || messages < 0) {
                throw new IllegalArgumentException("Runtime demand values must be non-negative and workers positive");
            }
            return this;
        }
    }

    public record Limits(int maxConcurrentRuns, int maxWorkerTasks, int maxWorkerTasksPerRun,
                         long maxRunDurationSeconds, Map<TestType, Integer> maxRunsByType,
                         Map<RunScope, Integer> maxRunsByScope,
                         Map<TestType, Integer> maxWorkersByType,
                         Map<RunScope, Integer> maxWorkersByScope,
                         long maxS3BytesPerRun, long maxClickHouseRowsPerRun,
                         long maxClickHouseQueriesPerRun, long maxKafkaMessagesPerRun) {
        static Limits compatible(int runs, int workers, long durationSeconds) {
            int safeRuns = Math.max(1, runs);
            int safeWorkers = Math.max(1, workers);
            return new Limits(safeRuns, safeWorkers, safeWorkers, Math.max(1, durationSeconds),
                    all(TestType.class, safeRuns), all(RunScope.class, safeRuns),
                    all(TestType.class, safeWorkers), all(RunScope.class, safeWorkers),
                    Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        }

        Limits normalized() {
            int runs = Math.max(1, maxConcurrentRuns);
            int workers = Math.max(1, maxWorkerTasks);
            return new Limits(runs, workers, Math.max(1, Math.min(workers, maxWorkerTasksPerRun)),
                    Math.max(1, maxRunDurationSeconds), normalize(TestType.class, maxRunsByType, runs),
                    normalize(RunScope.class, maxRunsByScope, runs),
                    normalize(TestType.class, maxWorkersByType, workers),
                    normalize(RunScope.class, maxWorkersByScope, workers),
                    Math.max(1, maxS3BytesPerRun), Math.max(1, maxClickHouseRowsPerRun),
                    Math.max(1, maxClickHouseQueriesPerRun), Math.max(1, maxKafkaMessagesPerRun));
        }

        private static <E extends Enum<E>> Map<E, Integer> normalize(
                Class<E> type, Map<E, Integer> values, int globalLimit) {
            EnumMap<E, Integer> result = new EnumMap<>(type);
            for (E value : type.getEnumConstants()) {
                int configured = values == null ? globalLimit : values.getOrDefault(value, globalLimit);
                result.put(value, Math.max(1, Math.min(globalLimit, configured)));
            }
            return Collections.unmodifiableMap(result);
        }

        private static <E extends Enum<E>> Map<E, Integer> all(Class<E> type, int value) {
            EnumMap<E, Integer> result = new EnumMap<>(type);
            for (E item : type.getEnumConstants()) result.put(item, value);
            return Collections.unmodifiableMap(result);
        }
    }

    public record CapacitySnapshot(RunScope nodeScope, int maxRuns, int activeRuns, int availableRuns,
                                   int maxWorkers, int activeWorkers, int availableWorkers,
                                   int maxWorkersPerRun, long maxRunDurationSeconds,
                                   Map<TestType, Capacity> byTestType,
                                   Map<RunScope, Capacity> byScope) { }

    public record Capacity(int maxRuns, int activeRuns, int availableRuns,
                           int maxWorkers, int activeWorkers, int availableWorkers) { }

    public static final class RunCapacityExceededException extends RejectedExecutionException {
        public RunCapacityExceededException(String message) {
            super(message);
        }
    }

    public static final class RunLimitExceededException extends IllegalArgumentException {
        public RunLimitExceededException(String message) {
            super(message);
        }
    }

    private static <E extends Enum<E>> Map<E, AtomicInteger> counters(Class<E> type) {
        EnumMap<E, AtomicInteger> result = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) result.put(value, new AtomicInteger());
        return result;
    }

    private static <E extends Enum<E>> Map<E, Semaphore> semaphores(
            Class<E> type, Map<E, Integer> limits) {
        EnumMap<E, Semaphore> result = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) result.put(value, new Semaphore(limits.get(value), true));
        return result;
    }
}
