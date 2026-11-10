package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.phibus.s3.credentials.CredentialProvider;
import dev.phibus.s3.runtime.RuntimeResourceManager;
import dev.phibus.s3.workload.WorkloadProfileCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

class UploadTestEngineTest {
    @Test
    void createsSessionCredentialsWhenTokenIsPresent() {
        assertTrue(UploadTestEngine.awsCredentials(
                new dev.phibus.s3.credentials.S3Credentials("access", "secret", "token"))
                instanceof AwsSessionCredentials);
        assertTrue(UploadTestEngine.awsCredentials(
                new dev.phibus.s3.credentials.S3Credentials("access", "secret"))
                instanceof AwsBasicCredentials);
    }

    @Test
    void failedSdkOperationIsVisibleInRunMetrics() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("object is unavailable").build());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             RuntimeResourceManager resources = resources()) {
            UploadTestEngine engine = new UploadTestEngine(mock(CredentialProvider.class),
                    mock(WorkloadProfileCatalog.class), request -> client, executor, resources);
            TestRun run = new TestRun(request("HEAD"));

            engine.execute(run);

            TestRun.Snapshot snapshot = run.snapshot();
            assertEquals(TestStatus.FAILED, snapshot.status());
            assertEquals(1, snapshot.completedParts());
            assertEquals(1, snapshot.failedParts());
            assertEquals(0, snapshot.successfulParts());
            assertEquals(1, snapshot.apiRequestCount());
            assertEquals(1, snapshot.objectOperationCount());
            assertEquals("FAILED", snapshot.parts().getFirst().status());
            assertTrue(snapshot.parts().getFirst().error().contains("object is unavailable"));
            verify(client).close();
        }
    }

    @Test
    void multipartUploadCountsEachUploadedPartAsOneOperation() {
        S3Client client = mock(S3Client.class);
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
        when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> UploadPartResponse.builder()
                        .eTag("etag-" + invocation.<UploadPartRequest>getArgument(0).partNumber()).build());
        when(client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().build());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             RuntimeResourceManager resources = resources()) {
            UploadTestEngine engine = new UploadTestEngine(mock(CredentialProvider.class),
                    mock(WorkloadProfileCatalog.class), request -> client, executor, resources);
            TestRequest request = new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                    true, "test.bin", 160, 32, 4, 1, false, "UPLOAD");
            TestRun run = new TestRun(request);

            engine.execute(run);

            TestRun.Snapshot snapshot = run.snapshot();
            assertEquals(TestStatus.COMPLETED, snapshot.status());
            assertEquals(5, snapshot.completedParts());
            assertEquals(5, snapshot.successfulParts());
            assertEquals(160L * 1024 * 1024, snapshot.bytesTransferred());
            assertEquals("MULTIPART_PART", snapshot.latencyScope());
            assertEquals(5, snapshot.latencySampleCount());
            assertEquals(7, snapshot.apiRequestCount());
            assertEquals(1, snapshot.objectOperationCount());
            verify(client, times(5)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
        }
    }

    @Test
    void cleanupRequestDoesNotPolluteMeasuredApiLatency() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             RuntimeResourceManager resources = resources()) {
            UploadTestEngine engine = new UploadTestEngine(mock(CredentialProvider.class),
                    mock(WorkloadProfileCatalog.class), request -> client, executor, resources);
            TestRequest request = new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                    true, "test.bin", 1, 5, 1, 1, true, "UPLOAD");
            TestRun run = new TestRun(request);

            engine.execute(run);

            TestRun.Snapshot snapshot = run.snapshot();
            assertEquals(TestStatus.COMPLETED, snapshot.status());
            assertEquals(1, snapshot.apiRequestCount());
            assertEquals(1, snapshot.objectOperationCount());
            verify(client).deleteObject(any(DeleteObjectRequest.class));
        }
    }

    private static TestRequest request(String operation) {
        return new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                true, "test.bin", 32, 8, 4, 1, false, operation);
    }

    private static RuntimeResourceManager resources() {
        return new RuntimeResourceManager(4, 16, 60, new SimpleMeterRegistry());
    }
}
