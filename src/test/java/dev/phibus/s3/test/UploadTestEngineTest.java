package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.phibus.s3.credentials.CredentialProvider;
import dev.phibus.s3.workload.WorkloadProfileCatalog;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

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
        UploadTestEngine engine = new UploadTestEngine(mock(CredentialProvider.class),
                mock(WorkloadProfileCatalog.class), request -> client);
        TestRun run = new TestRun(request("HEAD"));

        engine.execute(run);

        TestRun.Snapshot snapshot = run.snapshot();
        assertEquals(TestStatus.FAILED, snapshot.status());
        assertEquals(1, snapshot.completedParts());
        assertEquals(1, snapshot.failedParts());
        assertEquals(0, snapshot.successfulParts());
        assertEquals("FAILED", snapshot.parts().getFirst().status());
        assertTrue(snapshot.parts().getFirst().error().contains("object is unavailable"));
        verify(client).close();
    }

    private static TestRequest request(String operation) {
        return new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                true, "test.bin", 32, 8, 4, 1, false, operation);
    }
}
