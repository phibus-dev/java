package dev.phibus.s3.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.settings.S3ProfileService;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class IdempotentS3RunService {
    private static final String RESOURCE_TYPE = "S3_RUN";

    private final ApiIdempotencyCoordinator coordinator;
    private final S3ProfileService profiles;
    private final TestRunService runs;

    @Autowired
    public IdempotentS3RunService(ApiIdempotencyCoordinator coordinator, S3ProfileService profiles,
                                  TestRunService runs) {
        this.coordinator = coordinator;
        this.profiles = profiles;
        this.runs = runs;
    }

    /** Compatibility constructor retained for focused unit tests. */
    IdempotentS3RunService(ApiIdempotencyStore idempotency, S3ProfileService profiles,
                           TestRunService runs, ObjectMapper objectMapper) {
        this(new ApiIdempotencyCoordinator(idempotency, objectMapper), profiles, runs);
    }

    public StartResult start(String idempotencyKey, S3RunRequest suppliedRequest, String clientId) {
        S3RunRequest request = suppliedRequest == null ? null : suppliedRequest.normalized();
        ApiIdempotencyCoordinator.StartResult result = coordinator.start(
                idempotencyKey, request, clientId, RESOURCE_TYPE, runId -> {
                    requireProfile(request.profileId());
                    TestRun run = runs.create(runId, request.toTestRequest());
                    return run.id();
                });
        return new StartResult(result.runId(), result.replayed());
    }

    private void requireProfile(UUID profileId) {
        try {
            profiles.get(profileId);
        } catch (IllegalArgumentException error) {
            throw new ProfileNotFoundException("S3", profileId);
        }
    }

    public record StartResult(UUID runId, boolean replayed) { }

    public static final class ProfileNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProfileNotFoundException(UUID id) {
            this("S3", id);
        }

        public ProfileNotFoundException(String profileType, UUID id) {
            super(profileType + " profile not found: " + id);
        }
    }
}
