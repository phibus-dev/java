package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestRequestOperationTest {
    @Test
    void metadataOperationsDoNotReportTransferredPayload() {
        assertEquals(0, request("HEAD").totalBytes());
        assertEquals(0, request("LIST").totalBytes());
        assertEquals(0, request("DELETE").totalBytes());
    }

    @Test
    void lifecycleCountsUploadAndDownloadPayload() {
        assertEquals(400L * 1024 * 1024, request("LIFECYCLE").totalBytes());
    }

    private static TestRequest request(String operation) {
        return new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret", true,
                "test.bin", 100, 10, 4, 2, false, operation);
    }
}
