package dev.phibus.s3.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Global admission control for local and agent-side load generators.
 *
 * <p>Run permits bound the number of complete tests while worker permits bound
 * the aggregate concurrency created inside S3, ClickHouse and Kafka engines.
 * Permits are fail-fast: an overloaded node rejects a new run instead of
 * growing an unbounded queue or exhausting connections and memory.</p>
 */
@Component
public final class RuntimeResourceManager implements AutoCloseable {
    private final int maxConcurrentRuns;
    private final int maxWorkerTasks;
    private final Duration maxRunDuration;
    private final Semaphore runSlots;
    private final Semaphore workerSlots;
    private final AtomicInteger activeRuns = new AtomicInteger();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final ScheduledExecutorService deadlineScheduler;
    private final Counter rejectedRuns;
    private final Counter rejectedWorkers;
    private final Counter expiredRuns;

    public RuntimeResourceManager(
            @Value("${s3perf.runtime.max-concurrent-runs:8}") int maxConcurrentRuns,
            @Value("${s3perf.runtime.max-worker-tasks:64}") int maxWorkerTasks,
            @Value("${s3perf.runtime.max-run-duration-seconds:86400}") long maxRunDurationSeconds,
            MeterRegistry registry) {
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.maxWorkerTasks = Math.max(1, maxWorkerTasks);
        this.maxRunDuration = Duration.ofSeconds(Math.max(1, maxRunDurationSeconds));
        this.runSlots = new Semaphore(this.maxConcurrentRuns, true);
        this.workerSlots = new Semaphore(this.maxWorkerTasks, true);
        this.deadlineScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("run-deadline-", 0).factory());
        this.rejectedRuns = registry.counter("s3perf.runtime.rejections", "resource", "run");
        this.rejectedWorkers = registry.counter("s3perf.runtime.rejections", "resource", "worker");
        this.expiredRuns = registry.counter("s3perf.runtime.deadlines.exceeded");
        registry.gauge("s3perf.runtime.runs.active", activeRuns);
        registry.gauge("s3perf.runtime.workers.active", activeWorkers);
        registry.gauge("s3perf.runtime.runs.available", runSlots, Semaphore::availablePermits);
        registry.gauge("s3perf.runtime.workers.available", workerSlots, Semaphore::availablePermits);
    }

    public Future<?> submit(Executor executor, String workload, UUID runId,
                            Runnable deadlineAction, Runnable task) {
        RunPermit permit = acquireRun(workload, runId);
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
                // FutureTask never invokes its callable when it expires while it is
                // still queued. Close here as well as in the task body so admission
                // capacity cannot leak in that race; RunPermit.close is idempotent.
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
        int permits = Math.max(1, requested);
        if (permits > maxWorkerTasks || !workerSlots.tryAcquire(permits)) {
            rejectedWorkers.increment();
            throw new RunCapacityExceededException("Worker capacity exceeded for " + workload + " run " + runId
                    + ": requested=" + permits + ", available=" + workerSlots.availablePermits()
                    + ", limit=" + maxWorkerTasks);
        }
        activeWorkers.addAndGet(permits);
        return new WorkerPermit(permits);
    }

    public Map<String, String> telemetryTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("runtime.maxRuns", Integer.toString(maxConcurrentRuns));
        tags.put("runtime.activeRuns", Integer.toString(activeRuns.get()));
        tags.put("runtime.availableRuns", Integer.toString(runSlots.availablePermits()));
        tags.put("runtime.maxWorkers", Integer.toString(maxWorkerTasks));
        tags.put("runtime.activeWorkers", Integer.toString(activeWorkers.get()));
        tags.put("runtime.availableWorkers", Integer.toString(workerSlots.availablePermits()));
        return Map.copyOf(tags);
    }

    int activeRuns() { return activeRuns.get(); }
    int activeWorkers() { return activeWorkers.get(); }

    private RunPermit acquireRun(String workload, UUID runId) {
        if (!runSlots.tryAcquire()) {
            rejectedRuns.increment();
            throw new RunCapacityExceededException("Run capacity exceeded for " + workload + " run " + runId
                    + ": available=0, limit=" + maxConcurrentRuns);
        }
        activeRuns.incrementAndGet();
        return new RunPermit();
    }

    @PreDestroy
    @Override
    public void close() {
        deadlineScheduler.shutdownNow();
    }

    private final class RunPermit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> deadline;

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
            runSlots.release();
        }
    }

    public final class WorkerPermit implements AutoCloseable {
        private final int permits;
        private final AtomicBoolean closed = new AtomicBoolean();

        private WorkerPermit(int permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            activeWorkers.addAndGet(-permits);
            workerSlots.release(permits);
        }
    }

    public static final class RunCapacityExceededException extends RejectedExecutionException {
        public RunCapacityExceededException(String message) {
            super(message);
        }
    }
}
