package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.clickhouse.ClickHouseTestRun;
import dev.phibus.s3.clickhouse.ClickHouseTestRunService;
import dev.phibus.s3.distributed.DistributedTestService;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.kafka.KafkaProfileService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotentAutomationRunServiceTest {
    private final ApiIdempotencyStore store = mock(ApiIdempotencyStore.class);
    private final ClickHouseProfileService clickHouseProfiles = mock(ClickHouseProfileService.class);
    private final KafkaProfileService kafkaProfiles = mock(KafkaProfileService.class);
    private final ClickHouseTestRunService clickHouseRuns = mock(ClickHouseTestRunService.class);
    private final KafkaProducerRunService kafkaRuns = mock(KafkaProducerRunService.class);
    private final DistributedTestService distributedRuns = mock(DistributedTestService.class);
    private final IdempotentAutomationRunService service = new IdempotentAutomationRunService(
            new ApiIdempotencyCoordinator(store, new ObjectMapper().findAndRegisterModules()),
            clickHouseProfiles, kafkaProfiles, clickHouseRuns, kafkaRuns, distributedRuns);

    @Test
    void startsClickHouseAndKafkaWithTheClaimedStableIds() {
        when(store.claim(anyString(), anyString(), anyString(), anyString(), any(UUID.class)))
                .thenAnswer(invocation -> new ApiIdempotencyStore.Claim(invocation.getArgument(4), false));

        UUID clickHouseProfileId = UUID.randomUUID();
        ClickHouseTestRequest clickHouseRequest = new ClickHouseTestRequest(clickHouseProfileId, null,
                "events", "SELECT", 2, 1000, 10_000, 0, 0, 128, 20L, true);
        when(clickHouseProfiles.get(clickHouseProfileId)).thenReturn(new ClickHouseProfileService.Profile(
                clickHouseProfileId, "clickhouse", List.of("http://clickhouse:8123"), "default", "default",
                5000, 30, true, Instant.now(), Instant.now()));
        when(clickHouseRuns.create(any(UUID.class), eq(clickHouseRequest))).thenAnswer(invocation ->
                new ClickHouseTestRun(invocation.getArgument(0), clickHouseRequest, "http://clickhouse:8123"));

        ApiIdempotencyCoordinator.StartResult clickHouse = service.startClickHouse(
                "clickhouse-42", clickHouseRequest, "operator");

        assertThat(clickHouse.replayed()).isFalse();
        verify(clickHouseRuns).create(eq(clickHouse.runId()), eq(clickHouseRequest));

        UUID kafkaProfileId = UUID.randomUUID();
        KafkaProducerRunService.ProducerRequest kafkaRequest = new KafkaProducerRunService.ProducerRequest(
                kafkaProfileId, "events", 1024, 1000, 2, "all", "none",
                16_384, 5, true, 0, 10_000);
        when(kafkaProfiles.get(kafkaProfileId)).thenReturn(kafkaProfile(kafkaProfileId));
        when(kafkaRuns.start(any(UUID.class), eq(kafkaRequest), eq("operator"))).thenAnswer(invocation ->
                snapshot(invocation.getArgument(0), kafkaProfileId));

        ApiIdempotencyCoordinator.StartResult kafka = service.startKafka(
                "kafka-42", kafkaRequest, "operator");

        assertThat(kafka.replayed()).isFalse();
        verify(kafkaRuns).start(eq(kafka.runId()), eq(kafkaRequest), eq("operator"));
        assertThat(kafka.runId()).isNotEqualTo(clickHouse.runId());
    }

    private static KafkaProfileService.Profile kafkaProfile(UUID id) {
        return new KafkaProfileService.Profile(id, "kafka", "kafka:9092", "PLAINTEXT", null, null,
                "NONE", null, "password", null, false, null, "events", "evo-snt",
                10_000, Map.of(), true, Instant.now(), Instant.now());
    }

    private static KafkaProducerRunService.Snapshot snapshot(UUID id, UUID profileId) {
        Instant now = Instant.now();
        return new KafkaProducerRunService.Snapshot(id, profileId, "events", "RUNNING", now, null,
                1, 1000, 0, 0, 2, 1024, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }
}
