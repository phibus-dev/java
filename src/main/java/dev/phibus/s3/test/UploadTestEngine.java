package dev.phibus.s3.test;

import dev.phibus.s3.credentials.CredentialProvider;
import dev.phibus.s3.credentials.S3Credentials;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

@Component
public class UploadTestEngine {
    private static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    private static final int MAX_PARTS = 10_000;
    private final CredentialProvider credentialProvider;

    public UploadTestEngine(CredentialProvider credentialProvider) {
        this.credentialProvider = credentialProvider;
    }

    public void execute(TestRun run) {
        TestRequest request = run.request();
        try (S3Client client = client(request)) {
            switch (request.normalizedOperation()) {
                case "UPLOAD" -> executeUpload(client, run, request, true);
                case "DOWNLOAD" -> executeDownload(client, run, request);
                case "HEAD" -> executeHead(client, run, request);
                case "LIST" -> executeList(client, run, request);
                case "DELETE" -> executeDelete(client, run, request);
                case "LIFECYCLE" -> executeLifecycle(client, run, request);
                default -> throw new IllegalArgumentException("Unsupported operation: " + request.operation());
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

    private void executeUpload(S3Client client, TestRun run, TestRequest request, boolean initialize) throws Exception {
        long totalSize = request.objectSizeBytes();
        long partSize = normalizePartSize(request.partSizeBytes(), totalSize);
        int partsPerObject = totalSize < MIN_PART_SIZE ? 1 : (int) ((totalSize + partSize - 1) / partSize);
        if (initialize) run.start(Math.multiplyExact(partsPerObject, request.objectCount()));
        for (int objectNumber = 1; objectNumber <= request.objectCount(); objectNumber++) {
            checkCancelled(run);
            String key = objectKey(request, objectNumber);
            if (totalSize < MIN_PART_SIZE) uploadSingle(client, run, request, key, objectNumber, totalSize);
            else uploadMultipart(client, run, request, key, objectNumber, totalSize, partSize);
        }
        if (initialize && request.deleteAfterTest() && !run.isCancelled()) {
            deleteObjects(client, run, request, false);
            run.cleanupSuccessful();
        }
    }

    private void executeDownload(S3Client client, TestRun run, TestRequest request) {
        run.start(request.objectCount());
        for (int objectNumber = 1; objectNumber <= request.objectCount(); objectNumber++) {
            checkCancelled(run);
            downloadObject(client, run, request, objectNumber, objectNumber);
        }
    }

    private void executeHead(S3Client client, TestRun run, TestRequest request) {
        run.start(request.objectCount());
        for (int objectNumber = 1; objectNumber <= request.objectCount(); objectNumber++) {
            checkCancelled(run);
            headObject(client, run, request, objectNumber, objectNumber);
        }
    }

    private void executeList(S3Client client, TestRun run, TestRequest request) {
        run.start(1);
        Instant start = Instant.now();
        ListObjectsV2Response response = client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(request.bucket()).prefix(request.objectKey()).maxKeys(Math.min(1000, request.objectCount())).build());
        long duration = elapsedMillis(start);
        run.partCompleted(new PartResult(1, 1, 0, duration, 0,
                "objects=" + response.keyCount(), "SUCCESS", null));
    }

    private void executeDelete(S3Client client, TestRun run, TestRequest request) {
        run.start(request.objectCount());
        deleteObjects(client, run, request, true);
        run.cleanupSuccessful();
    }

    private void executeLifecycle(S3Client client, TestRun run, TestRequest request) throws Exception {
        long totalSize = request.objectSizeBytes();
        long partSize = normalizePartSize(request.partSizeBytes(), totalSize);
        int uploadParts = totalSize < MIN_PART_SIZE ? 1 : (int) ((totalSize + partSize - 1) / partSize);
        run.start(Math.addExact(Math.multiplyExact(uploadParts, request.objectCount()), Math.multiplyExact(3, request.objectCount())));
        executeUpload(client, run, request, false);
        for (int objectNumber = 1; objectNumber <= request.objectCount(); objectNumber++) {
            checkCancelled(run);
            headObject(client, run, request, objectNumber, uploadParts + 1);
            downloadObject(client, run, request, objectNumber, uploadParts + 2);
            deleteObject(client, run, request, objectNumber, uploadParts + 3);
        }
        run.cleanupSuccessful();
    }

    private void uploadSingle(S3Client client, TestRun run, TestRequest request, String key, int objectNumber, long size) {
        Instant start = Instant.now();
        try (InputStream input = new GeneratedInputStream(size, objectNumber)) {
            String eTag = client.putObject(PutObjectRequest.builder().bucket(request.bucket()).key(key).build(),
                    RequestBody.fromInputStream(input, size)).eTag();
            long duration = elapsedMillis(start);
            run.partCompleted(new PartResult(objectNumber, 1, size, duration, speed(size, duration), eTag, "SUCCESS", null));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void uploadMultipart(S3Client client, TestRun run, TestRequest request, String key,
                                 int objectNumber, long totalSize, long partSize) throws Exception {
        String uploadId = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(request.bucket()).key(key).build()).uploadId();
        ExecutorService pool = Executors.newFixedThreadPool(request.parallelism());
        try {
            List<Callable<CompletedPart>> tasks = new ArrayList<>();
            long offset = 0;
            for (int partNumber = 1; offset < totalSize; partNumber++) {
                long currentSize = Math.min(partSize, totalSize - offset);
                int currentPart = partNumber;
                long seed = (((long) objectNumber) << 32) ^ currentPart;
                tasks.add(() -> uploadPart(client, run, request, key, uploadId, objectNumber, currentPart, currentSize, seed));
                offset += currentSize;
            }
            List<Future<CompletedPart>> futures = pool.invokeAll(tasks);
            List<CompletedPart> completed = new ArrayList<>();
            for (Future<CompletedPart> future : futures) {
                checkCancelled(run);
                completed.add(future.get());
            }
            completed.sort(Comparator.comparingInt(CompletedPart::partNumber));
            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(request.bucket()).key(key)
                    .uploadId(uploadId).multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
        } catch (Exception e) {
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(request.bucket()).key(key).uploadId(uploadId).build());
            throw e;
        } finally { pool.shutdownNow(); }
    }

    private CompletedPart uploadPart(S3Client client, TestRun run, TestRequest request, String key, String uploadId,
                                     int objectNumber, int partNumber, long size, long seed) {
        checkCancelled(run);
        Instant start = Instant.now();
        try (InputStream input = new GeneratedInputStream(size, seed)) {
            String eTag = client.uploadPart(UploadPartRequest.builder().bucket(request.bucket()).key(key).uploadId(uploadId)
                            .partNumber(partNumber).contentLength(size).build(), RequestBody.fromInputStream(input, size)).eTag();
            long duration = elapsedMillis(start);
            run.partCompleted(new PartResult(objectNumber, partNumber, size, duration,
                    speed(size, duration), eTag, "SUCCESS", null));
            return CompletedPart.builder().partNumber(partNumber).eTag(eTag).build();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void downloadObject(S3Client client, TestRun run, TestRequest request, int objectNumber, int partNumber) {
        Instant start = Instant.now();
        GetObjectResponse response = client.getObject(GetObjectRequest.builder().bucket(request.bucket())
                        .key(objectKey(request, objectNumber)).build(),
                ResponseTransformer.toOutputStream(OutputStream.nullOutputStream()));
        long duration = elapsedMillis(start);
        long bytes = response.contentLength() == null ? request.objectSizeBytes() : response.contentLength();
        run.partCompleted(new PartResult(objectNumber, partNumber, bytes, duration,
                speed(bytes, duration), response.eTag(), "SUCCESS", null));
    }

    private void headObject(S3Client client, TestRun run, TestRequest request, int objectNumber, int partNumber) {
        Instant start = Instant.now();
        HeadObjectResponse response = client.headObject(HeadObjectRequest.builder().bucket(request.bucket())
                .key(objectKey(request, objectNumber)).build());
        long duration = elapsedMillis(start);
        run.partCompleted(new PartResult(objectNumber, partNumber, 0, duration, 0,
                "size=" + response.contentLength(), "SUCCESS", null));
    }

    private void deleteObjects(S3Client client, TestRun run, TestRequest request, boolean recordResults) {
        for (int objectNumber = 1; objectNumber <= request.objectCount(); objectNumber++) {
            checkCancelled(run);
            if (recordResults) deleteObject(client, run, request, objectNumber, objectNumber);
            else client.deleteObject(DeleteObjectRequest.builder().bucket(request.bucket())
                    .key(objectKey(request, objectNumber)).build());
        }
    }

    private void deleteObject(S3Client client, TestRun run, TestRequest request, int objectNumber, int partNumber) {
        Instant start = Instant.now();
        client.deleteObject(DeleteObjectRequest.builder().bucket(request.bucket())
                .key(objectKey(request, objectNumber)).build());
        long duration = elapsedMillis(start);
        run.partCompleted(new PartResult(objectNumber, partNumber, 0, duration, 0, "deleted", "SUCCESS", null));
    }

    private S3Client client(TestRequest request) {
        S3Credentials credentials = credentialProvider.resolve(request);
        return S3Client.builder().endpointOverride(URI.create(request.endpoint())).region(Region.of(request.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(credentials.accessKey(), credentials.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(request.pathStyleAccess()).build()).build();
    }

    private static void checkCancelled(TestRun run) {
        if (run.isCancelled()) throw new IllegalStateException("Test cancelled");
    }

    private static String objectKey(TestRequest request, int objectNumber) {
        return request.objectCount() == 1 ? request.objectKey() : request.objectKey() + "." + objectNumber;
    }

    private static long normalizePartSize(long requested, long total) {
        return Math.max(Math.max(MIN_PART_SIZE, requested), (total + MAX_PARTS - 1) / MAX_PARTS);
    }

    private static long elapsedMillis(Instant start) {
        return Math.max(1, Duration.between(start, Instant.now()).toMillis());
    }

    private static double speed(long bytes, long millis) {
        return bytes == 0 ? 0 : (bytes / 1024.0 / 1024.0) / (millis / 1000.0);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class GeneratedInputStream extends InputStream {
        private final long size;
        private final SplittableRandom random;
        private long position;
        GeneratedInputStream(long size, long seed) { this.size = size; this.random = new SplittableRandom(seed); }
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
