package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ApiIdempotencyCoordinatorTest {
    private final ApiIdempotencyStore store = mock(ApiIdempotencyStore.class);
    private final ApiIdempotencyCoordinator coordinator = new ApiIdempotencyCoordinator(
            store, new ObjectMapper().findAndRegisterModules());

    @Test
    void startsWithClaimedIdAndReturnsReplayWithoutStartingAgain() {
        when(store.claim(anyString(), anyString(), anyString(), eq("KAFKA_RUN"), any(UUID.class)))
                .thenAnswer(invocation -> new ApiIdempotencyStore.Claim(invocation.getArgument(4), false));
        AtomicInteger starts = new AtomicInteger();

        ApiIdempotencyCoordinator.StartResult first = coordinator.start(
                "pipeline-42", new Command("topic", 100), "operator", "KAFKA_RUN",
                runId -> {
                    starts.incrementAndGet();
                    return runId;
                });

        assertThat(first.replayed()).isFalse();
        assertThat(starts).hasValue(1);

        ApiIdempotencyStore replayStore = mock(ApiIdempotencyStore.class);
        when(replayStore.claim(anyString(), anyString(), anyString(), eq("KAFKA_RUN"), any(UUID.class)))
                .thenReturn(new ApiIdempotencyStore.Claim(first.runId(), true));
        ApiIdempotencyCoordinator replayCoordinator = new ApiIdempotencyCoordinator(
                replayStore, new ObjectMapper().findAndRegisterModules());

        ApiIdempotencyCoordinator.StartResult replay = replayCoordinator.start(
                "pipeline-42", new Command("topic", 100), "operator", "KAFKA_RUN",
                runId -> {
                    starts.incrementAndGet();
                    return runId;
                });

        assertThat(replay).isEqualTo(new ApiIdempotencyCoordinator.StartResult(first.runId(), true));
        assertThat(starts).hasValue(1);
    }

    @Test
    void rejectsUnsafeKeysBeforeTouchingPersistence() {
        assertThatThrownBy(() -> coordinator.start(
                "contains spaces", new Command("topic", 1), "operator", "KAFKA_RUN", runId -> runId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        verifyNoInteractions(store);
    }

    @Test
    void rejectsRunServiceThatChangesClaimedIdentity() {
        when(store.claim(anyString(), anyString(), anyString(), eq("CLICKHOUSE_RUN"), any(UUID.class)))
                .thenAnswer(invocation -> new ApiIdempotencyStore.Claim(invocation.getArgument(4), false));

        assertThatThrownBy(() -> coordinator.start(
                "pipeline-43", new Command("table", 1), "operator", "CLICKHOUSE_RUN",
                runId -> UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claimed run id");
    }

    private record Command(String target, long count) { }
}
