package dev.phibus.s3.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.phibus.s3.runtime.RuntimeResourceManager;
import dev.phibus.s3.test.TestType;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KafkaProducerRunServiceLimitTest {

    @Test
    void validatesKafkaDemandBeforeLoadingProfileOrWritingHistory() {
        KafkaProfileService profiles = mock(KafkaProfileService.class);
        KafkaConnectionService connections = mock(KafkaConnectionService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExecutorService workloadExecutor = mock(ExecutorService.class);
        RuntimeResourceManager resources = mock(RuntimeResourceManager.class);
        doThrow(new RuntimeResourceManager.RunLimitExceededException("message limit"))
                .when(resources).validateDemand(org.mockito.ArgumentMatchers.any());
        KafkaProducerRunService service = new KafkaProducerRunService(
                profiles, connections, jdbc, Runnable::run, workloadExecutor, resources);
        KafkaProducerRunService.ProducerRequest request = new KafkaProducerRunService.ProducerRequest(
                UUID.randomUUID(), "load-test", 1024, 50_000, 3,
                "all", "none", 16_384, 5, true, 0, 10_000);

        assertThatThrownBy(() -> service.start(request, "tester"))
                .isInstanceOf(RuntimeResourceManager.RunLimitExceededException.class)
                .hasMessageContaining("message limit");

        verify(resources).validateDemand(argThat(demand -> demand.testType() == TestType.KAFKA
                && demand.workerTasks() == 3 && demand.messages() == 50_000));
        verifyNoInteractions(profiles, connections, jdbc, workloadExecutor);
    }
}
