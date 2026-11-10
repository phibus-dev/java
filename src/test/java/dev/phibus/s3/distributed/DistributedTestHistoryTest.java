package dev.phibus.s3.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.phibus.s3.clickhouse.ClickHouseHistoryStore;
import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.kafka.KafkaProfileService;
import dev.phibus.s3.test.LatencyHistogramCodec;
import dev.phibus.s3.test.TestRequest;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DistributedTestHistoryTest {
    @Test
    void persistsTerminalAgentResultInCommonHistoryWithAgentName() {
        AgentRegistry registry = new AgentRegistry("registration-secret");
        AgentRegistry.RegistrationResult registration = registry.register(
                new AgentRegistry.RegistrationRequest("agent-east", "host", "http://agent", "2.2.3", 4,
                        1024, Map.of("capabilities", "S3")), "registration-secret");
        TestHistoryStore history = mock(TestHistoryStore.class);
        DistributedTestService service = new DistributedTestService(registry, mock(ClickHouseProfileService.class),
                mock(ClickHouseHistoryStore.class), history, mock(KafkaProfileService.class));
        TestRequest request = new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "object.bin", 10, 5, 2, 1, false, "UPLOAD");

        DistributedTestService.DistributedRunView run = service.create(
                new DistributedTestService.CreateDistributedTestRequest("run", List.of(registration.agentId()), request));
        service.report(registration.agentId(), registration.agentToken(),
                new DistributedTestService.AgentStatistics(run.id(), "COMPLETED", 2, 0, 10 * 1024 * 1024,
                        2.0, 5.0, 10, 20, 30, 0, "done", null));

        ArgumentCaptor<TestHistoryStore.DistributedHistoryResult> result =
                ArgumentCaptor.forClass(TestHistoryStore.DistributedHistoryResult.class);
        verify(history).saveDistributed(result.capture());
        assertThat(result.getValue().initiator()).isEqualTo("agent-east");
        assertThat(result.getValue().request()).isSameAs(request);
        assertThat(result.getValue().status()).isEqualTo("COMPLETED");
        assertThat(result.getValue().bytesTransferred()).isEqualTo(10 * 1024 * 1024);
        assertThat(result.getValue().id()).isNotEqualTo(run.id());
    }

    @Test
    void rejectsCredentialsAndFallsBackToWorstAgentPercentileForLegacyAgents() {
        AgentRegistry registry = new AgentRegistry("registration-secret");
        AgentRegistry.RegistrationResult first = register(registry, "agent-a");
        AgentRegistry.RegistrationResult second = register(registry, "agent-b");
        DistributedTestService service = new DistributedTestService(registry, mock(ClickHouseProfileService.class),
                mock(ClickHouseHistoryStore.class), mock(TestHistoryStore.class), mock(KafkaProfileService.class));
        TestRequest requestWithCredentials = new TestRequest("http://s3", "bucket", "us-east-1", "access", "secret",
                true, "object.bin", 10, 5, 2, 1, false, "UPLOAD");

        assertThatThrownBy(() -> service.create(new DistributedTestService.CreateDistributedTestRequest(
                "unsafe", List.of(first.agentId()), requestWithCredentials)))
                .hasMessageContaining("configured locally on each agent");

        TestRequest request = new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "object.bin", 10, 5, 2, 1, false, "UPLOAD");

        DistributedTestService.DistributedRunView run = service.create(
                new DistributedTestService.CreateDistributedTestRequest(
                        "run", List.of(first.agentId(), second.agentId()), request));
        assertThat(run.testRequest().accessKey()).isNull();
        assertThat(run.testRequest().secretKey()).isNull();

        service.report(first.agentId(), first.agentToken(), statistics(run.id(), 100, 20));
        DistributedTestService.DistributedRunView completed =
                service.report(second.agentId(), second.agentToken(), statistics(run.id(), 1, 100));

        assertThat(completed.p95LatencyMs()).isEqualTo(100);
        assertThat(completed.latencyScope()).isEqualTo("MULTIPART_PART");
        assertThat(completed.latencyAggregation()).isEqualTo("LEGACY_MAX");
    }

    @Test
    void mergesAgentHistogramsIntoTrueGlobalPercentiles() {
        AgentRegistry registry = new AgentRegistry("registration-secret");
        AgentRegistry.RegistrationResult first = register(registry, "agent-a");
        AgentRegistry.RegistrationResult second = register(registry, "agent-b");
        DistributedTestService service = new DistributedTestService(registry, mock(ClickHouseProfileService.class),
                mock(ClickHouseHistoryStore.class), mock(TestHistoryStore.class), mock(KafkaProfileService.class));
        TestRequest request = new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "object.bin", 10, 5, 2, 1, false, "UPLOAD");
        DistributedTestService.DistributedRunView run = service.create(
                new DistributedTestService.CreateDistributedTestRequest(
                        "run", List.of(first.agentId(), second.agentId()), request));

        Histogram fast = new Histogram(1, 10_000, 3);
        fast.recordValueWithCount(10, 100);
        Histogram slow = new Histogram(1, 10_000, 3);
        slow.recordValue(1_000);

        service.report(first.agentId(), first.agentToken(),
                statistics(run.id(), 100, 10, LatencyHistogramCodec.encode(fast)));
        DistributedTestService.DistributedRunView completed = service.report(second.agentId(), second.agentToken(),
                statistics(run.id(), 1, 1_000, LatencyHistogramCodec.encode(slow)));

        assertThat(completed.p95LatencyMs()).isEqualTo(10);
        assertThat(completed.p99LatencyMs()).isEqualTo(10);
        assertThat(completed.latencyAggregation()).isEqualTo("HDR_HISTOGRAM");
    }

    @Test
    void busyAgentCannotReceiveSecondAssignment() {
        AgentRegistry registry = new AgentRegistry("registration-secret");
        AgentRegistry.RegistrationResult agent = register(registry, "agent-a");
        DistributedTestService service = new DistributedTestService(registry, mock(ClickHouseProfileService.class),
                mock(ClickHouseHistoryStore.class), mock(TestHistoryStore.class), mock(KafkaProfileService.class));
        TestRequest request = new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "object.bin", 10, 5, 2, 1, false, "UPLOAD");
        var create = new DistributedTestService.CreateDistributedTestRequest(
                "run", List.of(agent.agentId()), request);

        service.create(create);

        assertThatThrownBy(() -> service.create(create)).hasMessageContaining("already busy");
    }

    @Test
    void failedAgentTakesPrecedenceOverAnEarlierCancellation() {
        AgentRegistry registry = new AgentRegistry("registration-secret");
        AgentRegistry.RegistrationResult first = register(registry, "agent-a");
        AgentRegistry.RegistrationResult second = register(registry, "agent-b");
        DistributedTestService service = new DistributedTestService(registry, mock(ClickHouseProfileService.class),
                mock(ClickHouseHistoryStore.class), mock(TestHistoryStore.class), mock(KafkaProfileService.class));
        TestRequest request = new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "object.bin", 10, 5, 2, 1, false, "UPLOAD");
        DistributedTestService.DistributedRunView run = service.create(
                new DistributedTestService.CreateDistributedTestRequest(
                        "run", List.of(first.agentId(), second.agentId()), request));

        service.report(first.agentId(), first.agentToken(),
                terminalStatistics(run.id(), "CANCELLED", "cancelled"));
        DistributedTestService.DistributedRunView completed = service.report(
                second.agentId(), second.agentToken(), terminalStatistics(run.id(), "FAILED", "failed"));

        assertThat(completed.status()).isEqualTo("FAILED");
        assertThat(completed.stopReason()).isEqualTo("ERROR");
    }

    private static AgentRegistry.RegistrationResult register(AgentRegistry registry, String name) {
        return registry.register(new AgentRegistry.RegistrationRequest(name, name + "-host", "http://" + name,
                "2.4.0", 4, 1024, Map.of("capabilities", "S3")), "registration-secret");
    }

    private static DistributedTestService.AgentStatistics statistics(java.util.UUID runId,
                                                                      long operations, double p95) {
        return new DistributedTestService.AgentStatistics(runId, "COMPLETED", operations, 0, 1024,
                1, 1, p95 / 2, p95, p95 * 2, 0, "done", null);
    }

    private static DistributedTestService.AgentStatistics statistics(java.util.UUID runId,
                                                                      long operations, double p95,
                                                                      String histogram) {
        return new DistributedTestService.AgentStatistics(runId, "COMPLETED", operations, 0, 1024,
                1, 1, p95 / 2, p95, p95 * 2, 0, "done", histogram);
    }

    private static DistributedTestService.AgentStatistics terminalStatistics(
            java.util.UUID runId, String status, String message) {
        return new DistributedTestService.AgentStatistics(runId, status, 0, 0, 0,
                0, 0, 0, 0, 0, 1, message, null);
    }
}
