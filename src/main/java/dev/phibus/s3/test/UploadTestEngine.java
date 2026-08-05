package dev.phibus.s3.test;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

@Component
public class UploadTestEngine {
    private static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    private static final int MAX_PARTS = 10_000;

    public void execute(TestRun run) {
        TestRequest request = run.request();
        long totalSize = request.objectSizeBytes();
        long partSize = normalizePartSize(request.partSizeBytes(), totalSize);
        int totalParts = (int) ((totalSize + partSize - 1) / partSize);
        run.start(totalParts);

        try (S3Client client = client(request)) {
            if (totalSize < MIN_PART_SIZE) {
                uploadSingle(client, run, request, totalSize);
            } else {
                uploadMultipart(client, run, request, totalSize, partSize);
            }
            if (!run.isCancelled()) run.complete();
        } catch (Exception e) {
            if (!run.isCancelled()) run.fail(rootMessage(e));
        }
    }

    public List<String> listBuckets(TestRequest request) {
        try (S3Client client = client(request)) {
            return client.listBuckets().buckets().stream().map(bucket -> bucket.name()).sorted().toList();
        }
    }

    private void uploadSingle(S3Client client, TestRun run, TestRequest request, long size) {
        Instant start = Instant.now();
        try (InputStream input = new GeneratedInputStream(size)) {
            client.putObject(PutObjectRequest.builder().bucket(request.bucket()).key(request.objectKey()).build(),
                    RequestBody.fromInputStream(input, size));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        double speed = speed(size, start);
        run.progress(size, 1, speed, speed);
    }

    private void uploadMultipart(S3Client client, TestRun run, TestRequest request, long totalSize, long partSize) {
        String uploadId = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(request.bucket()).key(request.objectKey()).build()).uploadId();
        List<CompletedPart> completed = new ArrayList<>();
        Instant overallStart = Instant.now();
        long transferred = 0;

        try (InputStream input = new GeneratedInputStream(totalSize)) {
            long remaining = totalSize;
            for (int partNumber = 1; remaining > 0; partNumber++) {
                if (run.isCancelled()) throw new InterruptedException("Test cancelled");
                long currentSize = Math.min(partSize, remaining);
                Instant partStart = Instant.now();
                String eTag = client.uploadPart(UploadPartRequest.builder()
                                .bucket(request.bucket()).key(request.objectKey()).uploadId(uploadId)
                                .partNumber(partNumber).contentLength(currentSize).build(),
                        RequestBody.fromInputStream(input, currentSize)).eTag();
                completed.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
                transferred += currentSize;
                run.progress(transferred, partNumber, speed(currentSize, partStart), speed(transferred, overallStart));
                remaining -= currentSize;
            }
            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(request.bucket()).key(request.objectKey()).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
        } catch (Exception e) {
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(request.bucket()).key(request.objectKey()).uploadId(uploadId).build());
            throw new IllegalStateException(e);
        }
    }

    private S3Client client(TestRequest request) {
        return S3Client.builder()
                .endpointOverride(URI.create(request.endpoint()))
                .region(Region.of(request.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(request.accessKey(), request.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(request.pathStyleAccess()).build())
                .build();
    }

    private static long normalizePartSize(long requested, long total) {
        long partSize = Math.max(MIN_PART_SIZE, requested);
        long required = (total + MAX_PARTS - 1) / MAX_PARTS;
        return Math.max(partSize, required);
    }

    private static double speed(long bytes, Instant start) {
        long millis = Math.max(1, Duration.between(start, Instant.now()).toMillis());
        return (bytes / 1024.0 / 1024.0) / (millis / 1000.0);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class GeneratedInputStream extends InputStream {
        private final long size;
        private final SplittableRandom random = new SplittableRandom();
        private long position;

        GeneratedInputStream(long size) { this.size = size; }
        @Override public int read() { if (position >= size) return -1; position++; return random.nextInt(256); }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (position >= size) return -1;
            int count = (int) Math.min(length, size - position);
            for (int i = offset; i < offset + count; i++) buffer[i] = (byte) random.nextInt(256);
            position += count;
            return count;
        }
    }
}
