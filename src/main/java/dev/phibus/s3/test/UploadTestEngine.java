package dev.phibus.s3.test;

import dev.phibus.s3.credentials.CredentialProvider;
import dev.phibus.s3.credentials.S3Credentials;
import dev.phibus.s3.workload.WeightedOperationSelector;
import dev.phibus.s3.workload.WorkloadProfileCatalog;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class UploadTestEngine {
    private static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    private static final int MAX_PARTS = 10_000;
    private final CredentialProvider credentialProvider;
    private final WorkloadProfileCatalog workloadProfiles;
    private final Function<TestRequest, S3Client> clientFactory;

    @Autowired
    public UploadTestEngine(CredentialProvider credentialProvider, WorkloadProfileCatalog workloadProfiles) {
        this(credentialProvider, workloadProfiles, null);
    }

    UploadTestEngine(CredentialProvider credentialProvider, WorkloadProfileCatalog workloadProfiles,
                     Function<TestRequest, S3Client> clientFactory) {
        this.credentialProvider = credentialProvider;
        this.workloadProfiles = workloadProfiles;
        this.clientFactory = clientFactory;
    }

    public void execute(TestRun run) {
        TestRequest request = run.request();
        if (run.isCancelled()) return;
        try (S3Client client = client(request)) {
            if (request.mixedWorkload()) executeMixed(client, run, request);
            else if (request.durationMode()) executeDuration(client, run, request);
            else executeCount(client, run, request);
            if (!run.isCancelled()) run.complete();
        } catch (Exception e) {
            if (!run.isCancelled()) run.fail(rootMessage(e));
        }
    }

    private void executeMixed(S3Client client, TestRun run, TestRequest request) throws Exception {
        if (!request.durationMode()) throw new IllegalArgumentException("MIXED workload requires TIME_DURATION mode");
        Map<String, Integer> weights = request.normalizedWorkloadWeights();
        if (weights.isEmpty()) weights = workloadProfiles.get(request.normalizedWorkloadProfile()).weights();
        WeightedOperationSelector selector = new WeightedOperationSelector(weights, run.id().getMostSignificantBits());
        run.start(0);
        String key = request.objectKey() + ".mixed-managed";
        boolean objectExists = false;
        long sequence = 0;
        long intervalNanos = request.targetOperationsPerSecond() > 0
                ? 1_000_000_000L / request.targetOperationsPerSecond() : 0;
        long nextStart = System.nanoTime();
        try {
            do {
                checkCancelled(run);
                if (intervalNanos > 0) {
                    long wait = nextStart - System.nanoTime();
                    if (wait > 0) LockSupport.parkNanos(wait);
                    nextStart = Math.max(nextStart + intervalNanos, System.nanoTime());
                }
                String operation = selector.next();
                int operationNumber = (int) Math.min(Integer.MAX_VALUE, ++sequence);
                switch (operation) {
                    case "UPLOAD" -> {
                        if (objectExists) client.deleteObject(DeleteObjectRequest.builder().bucket(request.bucket()).key(key).build());
                        uploadManagedObject(client, run, request, key, operationNumber);
                        objectExists = true;
                    }
                    case "DOWNLOAD" -> {
                        if (!objectExists) { uploadManagedObject(client, run, request, key, operationNumber); objectExists = true; }
                        downloadObject(client, run, request, operationNumber, operationNumber, key);
                    }
                    case "HEAD" -> {
                        if (!objectExists) { uploadManagedObject(client, run, request, key, operationNumber); objectExists = true; }
                        headObject(client, run, request, operationNumber, operationNumber, key);
                    }
                    case "LIST" -> executeListOperation(client, run, request, operationNumber);
                    case "DELETE" -> {
                        if (!objectExists) { uploadManagedObject(client, run, request, key, operationNumber); objectExists = true; }
                        deleteObject(client, run, request, operationNumber, operationNumber, key);
                        objectExists = false;
                    }
                    default -> throw new IllegalArgumentException("Unsupported mixed operation: " + operation);
                }
            } while (!run.durationExpired());
        } finally {
            if (objectExists) {
                client.deleteObject(DeleteObjectRequest.builder().bucket(request.bucket()).key(key).build());
                run.cleanupSuccessful();
            }
        }
    }

    private void uploadManagedObject(S3Client client, TestRun run, TestRequest request, String key, int number) throws Exception {
        long totalSize = request.objectSizeBytes();
        if (totalSize < MIN_PART_SIZE) uploadSingle(client, run, request, key, number, totalSize);
        else uploadMultipart(client, run, request, key, number, totalSize,
                normalizePartSize(request.partSizeBytes(), totalSize));
    }

    private void executeListOperation(S3Client client, TestRun run, TestRequest request, int operationNumber) {
        long started = System.nanoTime();
        try {
            ListObjectsV2Response response = client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(request.bucket()).prefix(request.objectKey()).maxKeys(Math.min(1000, request.objectCount())).build());
            long ms = elapsedMillis(started);
            run.partCompleted(new PartResult(operationNumber, operationNumber, 0, ms, 0,
                    "LIST objects=" + response.keyCount(), "SUCCESS", null));
        } catch (RuntimeException e) {
            run.operationFailed(operationNumber, operationNumber, "LIST", elapsedMillis(started), rootMessage(e));
            throw e;
        }
    }

    private void executeDuration(S3Client client, TestRun run, TestRequest request) throws Exception {
        run.start(0);
        long cycle = 0;
        do { checkCancelled(run); executeCycle(client, run, request, ++cycle, false); }
        while (!run.durationExpired());
    }

    private void executeCount(S3Client client, TestRun run, TestRequest request) throws Exception {
        executeCycle(client, run, request, 0, true);
    }

    private void executeCycle(S3Client client, TestRun run, TestRequest request, long cycle, boolean initialize) throws Exception {
        switch (request.normalizedOperation()) {
            case "UPLOAD" -> executeUpload(client, run, request, initialize, cycle);
            case "DOWNLOAD" -> executeDownload(client, run, request, initialize);
            case "HEAD" -> executeHead(client, run, request, initialize);
            case "LIST" -> executeList(client, run, request, initialize);
            case "DELETE" -> executeDelete(client, run, request, initialize);
            case "LIFECYCLE" -> executeLifecycle(client, run, request, initialize, cycle);
            default -> throw new IllegalArgumentException("Unsupported operation: " + request.operation());
        }
    }

    public List<String> listBuckets(TestRequest request) {
        try (S3Client client = client(request)) {
            return client.listBuckets().buckets().stream().map(Bucket::name).sorted().toList();
        }
    }

    private void executeUpload(S3Client client, TestRun run, TestRequest request, boolean initialize, long cycle) throws Exception {
        long totalSize = request.objectSizeBytes();
        long partSize = normalizePartSize(request.partSizeBytes(), totalSize);
        int partsPerObject = totalSize < MIN_PART_SIZE ? 1 : (int) ((totalSize + partSize - 1) / partSize);
        if (initialize) run.start(Math.multiplyExact(partsPerObject, request.objectCount()));
        for (int n = 1; n <= request.objectCount(); n++) {
            checkCancelled(run);
            String key = objectKey(request, n, cycle);
            if (totalSize < MIN_PART_SIZE) uploadSingle(client, run, request, key, n, totalSize);
            else uploadMultipart(client, run, request, key, n, totalSize, partSize);
            if ((request.deleteAfterTest() || request.durationMode()) && !run.isCancelled())
                client.deleteObject(DeleteObjectRequest.builder().bucket(request.bucket()).key(key).build());
        }
        if (request.deleteAfterTest() || request.durationMode()) run.cleanupSuccessful();
    }

    private void executeDownload(S3Client client, TestRun run, TestRequest request, boolean initialize) {
        if (initialize) run.start(request.objectCount());
        for (int n = 1; n <= request.objectCount(); n++) downloadObject(client, run, request, n, n, objectKey(request, n, 0));
    }

    private void executeHead(S3Client client, TestRun run, TestRequest request, boolean initialize) {
        if (initialize) run.start(request.objectCount());
        for (int n = 1; n <= request.objectCount(); n++) headObject(client, run, request, n, n, objectKey(request, n, 0));
    }

    private void executeList(S3Client client, TestRun run, TestRequest request, boolean initialize) {
        if (initialize) run.start(1);
        executeListOperation(client, run, request, 1);
    }

    private void executeDelete(S3Client client, TestRun run, TestRequest request, boolean initialize) {
        if (initialize) run.start(request.objectCount());
        for (int n = 1; n <= request.objectCount(); n++) deleteObject(client, run, request, n, n, objectKey(request, n, 0));
        run.cleanupSuccessful();
    }

    private void executeLifecycle(S3Client client, TestRun run, TestRequest request, boolean initialize, long cycle) throws Exception {
        long totalSize = request.objectSizeBytes();
        long partSize = normalizePartSize(request.partSizeBytes(), totalSize);
        int uploadParts = totalSize < MIN_PART_SIZE ? 1 : (int) ((totalSize + partSize - 1) / partSize);
        if (initialize) run.start(Math.multiplyExact(uploadParts + 3, request.objectCount()));
        for (int n = 1; n <= request.objectCount(); n++) {
            String key = objectKey(request, n, cycle);
            if (totalSize < MIN_PART_SIZE) uploadSingle(client, run, request, key, n, totalSize);
            else uploadMultipart(client, run, request, key, n, totalSize, partSize);
            headObject(client, run, request, n, uploadParts + 1, key);
            downloadObject(client, run, request, n, uploadParts + 2, key);
            deleteObject(client, run, request, n, uploadParts + 3, key);
        }
        run.cleanupSuccessful();
    }

    private void uploadSingle(S3Client client, TestRun run, TestRequest request, String key, int objectNumber, long size) {
        long started = System.nanoTime();
        try (InputStream input = new GeneratedInputStream(size, objectNumber)) {
            String eTag = client.putObject(PutObjectRequest.builder().bucket(request.bucket()).key(key).build(),
                    RequestBody.fromInputStream(input, size)).eTag();
            long ms = elapsedMillis(started);
            run.partCompleted(new PartResult(objectNumber, 1, size, ms, speed(size, ms), "UPLOAD " + eTag, "SUCCESS", null));
        } catch (Exception e) {
            run.operationFailed(objectNumber, 1, "UPLOAD", elapsedMillis(started), rootMessage(e));
            throw new IllegalStateException(e);
        }
    }

    private void uploadMultipart(S3Client client, TestRun run, TestRequest request, String key,
                                 int objectNumber, long totalSize, long partSize) throws Exception {
        long createStarted = System.nanoTime();
        String uploadId;
        try {
            uploadId = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(request.bucket()).key(key).build()).uploadId();
        } catch (RuntimeException e) {
            run.operationFailed(objectNumber, 0, "CREATE_MULTIPART_UPLOAD",
                    elapsedMillis(createStarted), rootMessage(e));
            throw e;
        }
        ExecutorService pool = Executors.newFixedThreadPool(request.parallelism());
        try {
            List<Callable<CompletedPart>> tasks = new ArrayList<>();
            long offset = 0;
            for (int part = 1; offset < totalSize; part++) {
                long size = Math.min(partSize, totalSize - offset); int partNumber = part;
                long seed = (((long) objectNumber) << 32) ^ partNumber;
                tasks.add(() -> uploadPart(client, run, request, key, uploadId, objectNumber, partNumber, size, seed));
                offset += size;
            }
            List<Future<CompletedPart>> futures = pool.invokeAll(tasks);
            List<CompletedPart> completed = new ArrayList<>();
            for (Future<CompletedPart> future : futures) completed.add(future.get());
            completed.sort(Comparator.comparingInt(CompletedPart::partNumber));
            long completeStarted = System.nanoTime();
            try {
                client.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(request.bucket()).key(key)
                        .uploadId(uploadId).multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
            } catch (RuntimeException e) {
                run.operationFailed(objectNumber, 0, "COMPLETE_MULTIPART_UPLOAD",
                        elapsedMillis(completeStarted), rootMessage(e));
                throw e;
            }
        } catch (Exception e) {
            try {
                client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(request.bucket()).key(key).uploadId(uploadId).build());
            } catch (RuntimeException abortError) {
                e.addSuppressed(abortError);
            }
            throw e;
        } finally { pool.shutdownNow(); }
    }

    private CompletedPart uploadPart(S3Client client, TestRun run, TestRequest request, String key, String uploadId,
                                     int objectNumber, int partNumber, long size, long seed) {
        checkCancelled(run); long started = System.nanoTime();
        try (InputStream input = new GeneratedInputStream(size, seed)) {
            String eTag = client.uploadPart(UploadPartRequest.builder().bucket(request.bucket()).key(key).uploadId(uploadId)
                    .partNumber(partNumber).contentLength(size).build(), RequestBody.fromInputStream(input, size)).eTag();
            long ms = elapsedMillis(started);
            run.partCompleted(new PartResult(objectNumber, partNumber, size, ms, speed(size, ms), "UPLOAD " + eTag, "SUCCESS", null));
            return CompletedPart.builder().partNumber(partNumber).eTag(eTag).build();
        } catch (Exception e) {
            run.operationFailed(objectNumber, partNumber, "UPLOAD_PART", elapsedMillis(started), rootMessage(e));
            throw new IllegalStateException(e);
        }
    }

    private void downloadObject(S3Client client, TestRun run, TestRequest request, int objectNumber, int partNumber, String key) {
        checkCancelled(run); long started = System.nanoTime();
        try {
            GetObjectResponse response = client.getObject(GetObjectRequest.builder().bucket(request.bucket()).key(key).build(),
                    ResponseTransformer.toOutputStream(OutputStream.nullOutputStream()));
            long ms = elapsedMillis(started);
            long bytes = response.contentLength() == null ? request.objectSizeBytes() : response.contentLength();
            run.partCompleted(new PartResult(objectNumber, partNumber, bytes, ms, speed(bytes, ms),
                    "DOWNLOAD " + response.eTag(), "SUCCESS", null));
        } catch (RuntimeException e) {
            run.operationFailed(objectNumber, partNumber, "DOWNLOAD", elapsedMillis(started), rootMessage(e));
            throw e;
        }
    }

    private void headObject(S3Client client, TestRun run, TestRequest request, int objectNumber, int partNumber, String key) {
        checkCancelled(run); long started = System.nanoTime();
        try {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder().bucket(request.bucket()).key(key).build());
            long ms = elapsedMillis(started);
            run.partCompleted(new PartResult(objectNumber, partNumber, 0, ms, 0,
                    "HEAD size=" + response.contentLength(), "SUCCESS", null));
        } catch (RuntimeException e) {
            run.operationFailed(objectNumber, partNumber, "HEAD", elapsedMillis(started), rootMessage(e));
            throw e;
        }
    }

    private void deleteObject(S3Client client, TestRun run, TestRequest request, int objectNumber, int partNumber, String key) {
        checkCancelled(run); long started = System.nanoTime();
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(request.bucket()).key(key).build());
            long ms = elapsedMillis(started);
            run.partCompleted(new PartResult(objectNumber, partNumber, 0, ms, 0, "DELETE", "SUCCESS", null));
        } catch (RuntimeException e) {
            run.operationFailed(objectNumber, partNumber, "DELETE", elapsedMillis(started), rootMessage(e));
            throw e;
        }
    }

    private S3Client client(TestRequest request) {
        if (clientFactory != null) return clientFactory.apply(request);
        S3Credentials credentials = credentialProvider.resolve(request);
        return S3Client.builder().endpointOverride(URI.create(request.endpoint())).region(Region.of(request.region()))
                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials(credentials)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(request.pathStyleAccess()).build()).build();
    }

    static AwsCredentials awsCredentials(S3Credentials credentials) {
        return credentials.temporary()
                ? AwsSessionCredentials.create(credentials.accessKey(), credentials.secretKey(), credentials.sessionToken())
                : AwsBasicCredentials.create(credentials.accessKey(), credentials.secretKey());
    }

    private static void checkCancelled(TestRun run) { if (run.isCancelled()) throw new IllegalStateException("Test cancelled"); }
    private static String objectKey(TestRequest request, int number, long cycle) {
        String base = request.objectCount() == 1 ? request.objectKey() : request.objectKey() + "." + number;
        return cycle > 0 && ("UPLOAD".equals(request.normalizedOperation()) || "LIFECYCLE".equals(request.normalizedOperation()))
                ? base + ".run-" + cycle : base;
    }
    private static long normalizePartSize(long requested, long total) {
        return Math.max(Math.max(MIN_PART_SIZE, requested), (total + MAX_PARTS - 1) / MAX_PARTS);
    }
    private static long elapsedMillis(long startedNanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }
    private static double speed(long bytes, long millis) { return bytes == 0 ? 0 : (bytes / 1048576.0) / (millis / 1000.0); }
    private static String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class GeneratedInputStream extends InputStream {
        private final long size; private final SplittableRandom random; private long position;
        GeneratedInputStream(long size, long seed) { this.size = size; this.random = new SplittableRandom(seed); }
        @Override public int read() { if (position >= size) return -1; position++; return random.nextInt(256); }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (position >= size) return -1; int count = (int) Math.min(length, size - position);
            int end = offset + count;
            int index = offset;
            while (index + Long.BYTES <= end) {
                long value = random.nextLong();
                for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                    buffer[index++] = (byte) (value >>> shift);
                }
            }
            while (index < end) buffer[index++] = (byte) random.nextInt(256);
            position += count; return count;
        }
    }
}
