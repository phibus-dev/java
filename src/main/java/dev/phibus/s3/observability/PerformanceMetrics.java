package dev.phibus.s3.observability;

import dev.phibus.s3.distributed.AgentRegistry;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import dev.phibus.s3.test.TestStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class PerformanceMetrics {
    private final MeterRegistry registry;
    private final TestRunService testRuns;
    private final AgentRegistry agents;

    public PerformanceMetrics(MeterRegistry registry, TestRunService testRuns, AgentRegistry agents) {
        this.registry = registry;
        this.testRuns = testRuns;
        this.agents = agents;
    }

    @PostConstruct
    void register() {
        Gauge.builder("s3_test_active", testRuns, service -> countStatus(service, TestStatus.RUNNING))
                .description("Number of currently running local S3 tests").register(registry);
        Gauge.builder("s3_test_runs", testRuns, service -> service.list().size())
                .description("Number of local S3 test runs held by the application").register(registry);
        Gauge.builder("s3_test_transferred_bytes", testRuns, PerformanceMetrics::sumTransferredBytes)
                .baseUnit("bytes").description("Bytes transferred by local S3 test runs").register(registry);
        Gauge.builder("s3_test_throughput_mibps", testRuns, PerformanceMetrics::sumActiveThroughput)
                .baseUnit("MiB/s").description("Combined throughput of active local S3 tests").register(registry);
        Gauge.builder("s3_test_operations_per_second", testRuns, PerformanceMetrics::sumActiveOperations)
                .description("Combined operation rate of active local S3 tests").register(registry);
        Gauge.builder("s3_test_errors", testRuns, PerformanceMetrics::sumErrors)
                .description("Errors recorded by local S3 tests").register(registry);
        Gauge.builder("s3_test_p95_latency_milliseconds", testRuns, PerformanceMetrics::maxActiveP95)
                .baseUnit("milliseconds").description("Highest p95 latency among active local tests").register(registry);
        Gauge.builder("s3_agents_online", agents, registry -> countAgents(registry, AgentRegistry.AgentStatus.ONLINE))
                .description("Number of online distributed test agents").register(this.registry);
        Gauge.builder("s3_agents_busy", agents, registry -> countAgents(registry, AgentRegistry.AgentStatus.BUSY))
                .description("Number of busy distributed test agents").register(this.registry);
        Gauge.builder("s3_agents_offline", agents, registry -> countAgents(registry, AgentRegistry.AgentStatus.OFFLINE))
                .description("Number of offline distributed test agents").register(this.registry);
    }

    private static double countStatus(TestRunService service, TestStatus status) {
        return service.list().stream().filter(run -> run.status() == status).count();
    }

    private static double sumTransferredBytes(TestRunService service) {
        return service.list().stream().mapToDouble(TestRun.Snapshot::bytesTransferred).sum();
    }

    private static double sumActiveThroughput(TestRunService service) {
        return service.list().stream().filter(PerformanceMetrics::active)
                .mapToDouble(TestRun.Snapshot::averageSpeedMiBps).sum();
    }

    private static double sumActiveOperations(TestRunService service) {
        return service.list().stream().filter(PerformanceMetrics::active)
                .mapToDouble(TestRun.Snapshot::operationsPerSecond).sum();
    }

    private static double sumErrors(TestRunService service) {
        return service.list().stream().mapToDouble(TestRun.Snapshot::failedParts).sum();
    }

    private static double maxActiveP95(TestRunService service) {
        return service.list().stream().filter(PerformanceMetrics::active)
                .mapToDouble(TestRun.Snapshot::p95LatencyMs).max().orElse(0);
    }

    private static boolean active(TestRun.Snapshot run) {
        return run.status() == TestStatus.RUNNING;
    }

    private static double countAgents(AgentRegistry registry, AgentRegistry.AgentStatus status) {
        return registry.list().stream().filter(agent -> agent.status() == status).count();
    }
}
