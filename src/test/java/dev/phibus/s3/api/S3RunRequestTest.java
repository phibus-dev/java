package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class S3RunRequestTest {
    @Test
    void omittedAndExplicitDefaultsHaveTheSameNormalizedFingerprintInput() {
        UUID profileId = UUID.randomUUID();
        S3RunRequest omitted = new S3RunRequest(profileId, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        S3RunRequest explicit = new S3RunRequest(profileId, "performance-test/random-upload.bin",
                32L, 8L, 4, 1, true, "UPLOAD", "OBJECT_COUNT", 60L, 0L,
                "CUSTOM", Map.of(), 0, Map.of());

        assertThat(omitted.normalized()).isEqualTo(explicit.normalized());
    }

    @Test
    void mapKeysAreCanonicalAndSorted() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("upload", 70);
        weights.put("head", 30);
        S3RunRequest request = new S3RunRequest(UUID.randomUUID(), null, null, null, null, null,
                null, "MIXED", "TIME_DURATION", null, null, null, weights, null, null);

        assertThat(request.normalized().workloadWeights())
                .containsEntry("HEAD", 30)
                .containsEntry("UPLOAD", 70);
        assertThat(request.normalized().workloadWeights().keySet())
                .containsExactly("HEAD", "UPLOAD");
    }

    @Test
    void semanticValidationAlsoProtectsNonHttpCallers() {
        S3RunRequest request = new S3RunRequest(UUID.randomUUID(), null, 0L, null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(request::normalized)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objectSizeMiB");
    }

    @Test
    void rejectsMixedWorkloadsThatWouldFailAfterBeingAccepted() {
        S3RunRequest countMode = new S3RunRequest(UUID.randomUUID(), null, null, null, null, null,
                null, "MIXED", "OBJECT_COUNT", null, null, "INGESTION", null, null, null);
        S3RunRequest missingCustomWeights = new S3RunRequest(UUID.randomUUID(), null, null, null, null, null,
                null, "MIXED", "TIME_DURATION", null, null, "CUSTOM", null, null, null);
        S3RunRequest invalidTotal = new S3RunRequest(UUID.randomUUID(), null, null, null, null, null,
                null, "MIXED", "TIME_DURATION", null, null, "CUSTOM",
                Map.of("UPLOAD", 80), null, null);

        assertThatThrownBy(countMode::normalized)
                .hasMessageContaining("TIME_DURATION");
        assertThatThrownBy(missingCustomWeights::normalized)
                .hasMessageContaining("workloadWeights");
        assertThatThrownBy(invalidTotal::normalized)
                .hasMessageContaining("total 100");
    }
}
