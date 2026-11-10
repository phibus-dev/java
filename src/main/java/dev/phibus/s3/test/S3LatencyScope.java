package dev.phibus.s3.test;

/** Describes what one sample in the legacy S3 latency histogram represents. */
public enum S3LatencyScope {
    MULTIPART_PART,
    OBJECT_REQUEST,
    RECORDED_STEP;

    private static final long MIN_MULTIPART_SIZE_BYTES = 5L * 1024 * 1024;

    public static S3LatencyScope forRequest(TestRequest request) {
        if (request.mixedWorkload() || "LIFECYCLE".equals(request.normalizedOperation())) {
            return RECORDED_STEP;
        }
        if ("UPLOAD".equals(request.normalizedOperation())
                && request.objectSizeBytes() >= MIN_MULTIPART_SIZE_BYTES) {
            return MULTIPART_PART;
        }
        return OBJECT_REQUEST;
    }
}
