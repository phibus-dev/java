package dev.phibus.s3.web;

import dev.phibus.s3.test.TestRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;

public record BucketListRequest(
        UUID profileId,
        @NotBlank String endpoint,
        @NotBlank String region,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess) {

    TestRequest toTestRequest() {
        return new TestRequest(
                endpoint,
                "__bucket_listing__",
                region,
                accessKey,
                secretKey,
                pathStyleAccess,
                "__bucket_listing__",
                1,
                5,
                1,
                1,
                false,
                "LIST",
                "OBJECT_COUNT",
                60,
                0,
                "CUSTOM",
                Map.of(),
                0,
                Map.of(),
                profileId);
    }
}
