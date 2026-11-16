package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.settings.S3ProfileService;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotentS3RunServiceTest {
    private final ApiIdempotencyStore idempotency = mock(ApiIdempotencyStore.class);
    private final S3ProfileService profiles = mock(S3ProfileService.class);
    private final TestRunService runs = mock(TestRunService.class);
    private final IdempotentS3RunService service = new IdempotentS3RunService(
            idempotency, profiles, runs, new ObjectMapper().findAndRegisterModules());

    @Test
    void startsANewRunWithTheClaimedStableId() {
        S3RunRequest request = request();
        when(idempotency.claim(anyString(), anyString(), anyString(), eq("S3_RUN"), any(UUID.class)))
                .thenAnswer(invocation -> new ApiIdempotencyStore.Claim(invocation.getArgument(4), false));
        when(runs.create(any(UUID.class), any(TestRequest.class))).thenAnswer(invocation -> {
            TestRun run = mock(TestRun.class);
            when(run.id()).thenReturn(invocation.getArgument(0));
            return run;
        });

        IdempotentS3RunService.StartResult result = service.start("pipeline-42", request, "operator");

        assertThat(result.replayed()).isFalse();
        verify(profiles).get(request.profileId());
        verify(runs).create(eq(result.runId()), any(TestRequest.class));
    }

    @Test
    void replayDoesNotValidateADeletedProfileOrStartAnotherRun() {
        UUID existingRun = UUID.randomUUID();
        when(idempotency.claim(anyString(), anyString(), anyString(), eq("S3_RUN"), any(UUID.class)))
                .thenReturn(new ApiIdempotencyStore.Claim(existingRun, true));

        IdempotentS3RunService.StartResult result = service.start("pipeline-42", request(), "operator");

        assertThat(result).isEqualTo(new IdempotentS3RunService.StartResult(existingRun, true));
        verifyNoInteractions(profiles, runs);
    }

    @Test
    void rejectsUnsafeKeysAndMissingProfiles() {
        assertThatThrownBy(() -> service.start("contains spaces", request(), "operator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");

        when(idempotency.claim(anyString(), anyString(), anyString(), eq("S3_RUN"), any(UUID.class)))
                .thenAnswer(invocation -> new ApiIdempotencyStore.Claim(invocation.getArgument(4), false));
        when(profiles.get(any(UUID.class))).thenThrow(new IllegalArgumentException("not found"));
        assertThatThrownBy(() -> service.start("pipeline-43", request(), "operator"))
                .isInstanceOf(IdempotentS3RunService.ProfileNotFoundException.class);
    }

    private static S3RunRequest request() {
        return new S3RunRequest(UUID.randomUUID(), "ci.bin", 32L, 8L, 4, 1,
                true, "UPLOAD", "OBJECT_COUNT", 60L, 0L,
                "CUSTOM", Map.of(), 0, Map.of());
    }
}
