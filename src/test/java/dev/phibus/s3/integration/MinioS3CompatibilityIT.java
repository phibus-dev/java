package dev.phibus.s3.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Testcontainers
class MinioS3CompatibilityIT {

    private static final String ACCESS_KEY = "integration-access";
    private static final String SECRET_KEY = "integration-secret-key";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data", "--address", ":9000")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @Test
    void putHeadGetListAndDeleteWorkWithPathStyleAccess() {
        String bucket = "integration-bucket";
        String key = "tests/object.txt";
        byte[] payload = "s3-performance-integration-test".getBytes(StandardCharsets.UTF_8);

        try (S3Client client = client()) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(payload));

            assertThat(client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength())
                    .isEqualTo(payload.length);
            assertThat(client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray())
                    .isEqualTo(payload);
            assertThat(client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix("tests/").build())
                    .contents()).extracting(object -> object.key()).containsExactly(key);

            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            assertThat(client.listBuckets().buckets()).isEmpty();
        }
    }

    private static S3Client client() {
        URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
