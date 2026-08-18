package dev.phibus.s3.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.phibus.s3.clickhouse.ClickHouseHistoryStore;
import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.test.TestRequest;
import java.util.List;
import java.util.Map;
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
                mock(ClickHouseHistoryStore.class), history);
        TestRequest request = new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "object.bin", 10, 5, 2, 1, false, "UPLOAD");

        DistributedTestService.DistributedRunView run = service.create(
                new DistributedTestService.CreateDistributedTestRequest("run", List.of(registration.agentId()), request));
        service.report(registration.agentId(), registration.agentToken(),
                new DistributedTestService.AgentStatistics(run.id(), "COMPLETED", 2, 0, 10 * 1024 * 1024,
                        2.0, 5.0, 10, 20, 30, 0, "done"));

        ArgumentCaptor<TestHistoryStore.DistributedHistoryResult> result =
                ArgumentCaptor.forClass(TestHistoryStore.DistributedHistoryResult.class);
        verify(history).saveDistributed(result.capture());
        assertThat(result.getValue().initiator()).isEqualTo("agent-east");
        assertThat(result.getValue().request()).isSameAs(request);
        assertThat(result.getValue().status()).isEqualTo("COMPLETED");
        assertThat(result.getValue().bytesTransferred()).isEqualTo(10 * 1024 * 1024);
        assertThat(result.getValue().id()).isNotEqualTo(run.id());
    }
}
