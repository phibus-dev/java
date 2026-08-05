package dev.phibus.s3;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

public final class S3MultipartUploader {
    static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    static final long DEFAULT_PART_SIZE = 64L * 1024 * 1024;
    static final int MAX_PARTS = 10_000;

    private S3MultipartUploader() {
    }

    public static void main(String[] args) {
        try {
            Config config = Config.fromEnvironment(args);
            try (S3Client client = buildClient(config)) {
                upload(client, config);
            }
        } catch (Exception e) {
            System.err.println("Upload failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void upload(S3Client client, Config config) throws Exception {
        String key = config.objectKey();
        System.out.printf("Uploading s3://%s/%s (%,d bytes)%n", config.bucket(), key, config.sizeBytes());
        if (config.sizeBytes() < MIN_PART_SIZE) {
            singlePut(client, config, key);
        } else {
            multipartPut(client, config, key);
        }
    }

    static S3Client buildClient(Config config) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(config.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.pathStyleAccess())
                        .build());
        if (config.endpoint() != null) {
            builder.endpointOverride(URI.create(config.endpoint()));
        }
        if (config.accessKey() != null && config.secretKey() != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKey(), config.secretKey())));
        }
        return builder.build();
    }

    private static void singlePut(S3Client client, Config config, String key) throws IOException {
        Instant start = Instant.now();
        try (InputStream input = new RandomInputStream(config.sizeBytes())) {
            client.putObject(PutObjectRequest.builder().bucket(config.bucket()).key(key).build(),
                    RequestBody.fromInputStream(input, config.sizeBytes()));
        }
        printSpeed("PutObject completed", config.sizeBytes(), start);
    }

    private static void multipartPut(S3Client client, Config config, String key) throws Exception {
        long partSize = normalizePartSize(config.partSizeBytes(), config.sizeBytes());
        long partCount = partCount(config.sizeBytes(), partSize);
        String uploadId = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(config.bucket()).key(key).build()).uploadId();
        List<CompletedPart> completed = new ArrayList<>((int) partCount);
        Instant overallStart = Instant.now();

        try (InputStream input = new RandomInputStream(config.sizeBytes())) {
            long remaining = config.sizeBytes();
            for (int partNumber = 1; remaining > 0; partNumber++) {
                long currentSize = Math.min(partSize, remaining);
                Instant partStart = Instant.now();
                String eTag = client.uploadPart(UploadPartRequest.builder()
                                .bucket(config.bucket()).key(key).uploadId(uploadId)
                                .partNumber(partNumber).contentLength(currentSize).build(),
                        RequestBody.fromInputStream(input, currentSize)).eTag();
                completed.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
                printSpeed("Part " + partNumber + " completed", currentSize, partStart);
                remaining -= currentSize;
            }
            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(config.bucket()).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
            printSpeed("Multipart upload completed", config.sizeBytes(), overallStart);
        } catch (Exception e) {
            try {
                client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(config.bucket()).key(key).uploadId(uploadId).build());
            } catch (Exception abortError) {
                e.addSuppressed(abortError);
            }
            throw e;
        }
    }

    static long normalizePartSize(long requestedPartSize, long totalSize) {
        long partSize = Math.max(MIN_PART_SIZE, requestedPartSize);
        long minimumForPartLimit = (totalSize + MAX_PARTS - 1) / MAX_PARTS;
        return Math.max(partSize, minimumForPartLimit);
    }

    static long partCount(long totalSize, long partSize) {
        if (totalSize <= 0 || partSize <= 0) {
            throw new IllegalArgumentException("Size and part size must be positive");
        }
        long count = (totalSize + partSize - 1) / partSize;
        if (count > MAX_PARTS) {
            throw new IllegalArgumentException("Multipart upload would exceed 10,000 parts");
        }
        return count;
    }

    private static void printSpeed(String label, long bytes, Instant start) {
        long millis = Math.max(1, Duration.between(start, Instant.now()).toMillis());
        double mibPerSecond = (bytes / 1024.0 / 1024.0) / (millis / 1000.0);
        System.out.printf("%s: %,d bytes in %d ms (%.2f MiB/s)%n", label, bytes, millis, mibPerSecond);
    }

    record Config(String bucket, String region, String endpoint, String accessKey, String secretKey,
                  boolean pathStyleAccess, long sizeBytes, long partSizeBytes, String objectKey) {
        static Config fromEnvironment(String[] args) {
            String bucket = requiredEnv("S3_BUCKET");
            String region = requiredEnv("S3_REGION");
            long size = args.length > 0 ? parsePositiveLong(args[0], "size")
                    : parsePositiveLong(envOrDefault("S3_SIZE_BYTES", "1073741824"), "S3_SIZE_BYTES");
            long partSize = parsePositiveLong(
                    envOrDefault("S3_PART_SIZE_BYTES", String.valueOf(DEFAULT_PART_SIZE)),
                    "S3_PART_SIZE_BYTES");
            String key = envOrDefault("S3_OBJECT_KEY", "random-upload-" + System.currentTimeMillis() + ".bin");
            return new Config(bucket, region, blankToNull(System.getenv("S3_ENDPOINT")),
                    blankToNull(System.getenv("S3_ACCESS_KEY")), blankToNull(System.getenv("S3_SECRET_KEY")),
                    Boolean.parseBoolean(envOrDefault("S3_PATH_STYLE", "true")), size, partSize, key);
        }

        private static String requiredEnv(String name) {
            String value = blankToNull(System.getenv(name));
            if (value == null) {
                throw new IllegalArgumentException("Missing environment variable: " + name);
            }
            return value;
        }

        private static String envOrDefault(String name, String defaultValue) {
            String value = blankToNull(System.getenv(name));
            return value == null ? defaultValue : value;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static long parsePositiveLong(String value, String name) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed <= 0) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a positive integer: " + value, e);
            }
        }
    }

    static final class RandomInputStream extends InputStream {
        private final long size;
        private final SplittableRandom random = new SplittableRandom();
        private long position;

        RandomInputStream(long size) {
            if (size < 0) {
                throw new IllegalArgumentException("size must be non-negative");
            }
            this.size = size;
        }

        @Override
        public int read() {
            if (position >= size) {
                return -1;
            }
            position++;
            return random.nextInt(256);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= size) {
                return -1;
            }
            int count = (int) Math.min(length, size - position);
            for (int i = offset; i < offset + count; i++) {
                buffer[i] = (byte) random.nextInt(256);
            }
            position += count;
            return count;
        }
    }
}
