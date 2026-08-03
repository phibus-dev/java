import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Configuration;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3MultipartUploader {

    // Минимальный размер для multipart-part (S3 требует минимум 5 MiB для всех частей, кроме последней)
    private static final long MIN_PART_SIZE = 5L * 1024L * 1024L; // 5 MiB
    // Предлагаемый размор части
    private static final long DEFAULT_PART_SIZE = 64L * 1024L * 1024L; // 64 MiB

    public static void main(String[] args) {
        // Читаем настройки из переменных окружения
        String endpoint = System.getenv("S3_ENDPOINT"); // e.g. https://play.min.io or https://s3.amazonaws.com
        String region = System.getenv("S3_REGION"); // e.g. us-east-1
        String bucket = System.getenv("S3_BUCKET");
        String accessKey = System.getenv("S3_ACCESS_KEY");
        String secretKey = System.getenv("S3_SECRET_KEY");

        if (bucket == null || region == null) {
            System.err.println("Необходимо задать переменные окружения S3_BUCKET и S3_REGION (S3_ENDPOINT, S3_ACCESS_KEY, S3_SECRET_KEY опциональны для AWS)");
            System.exit(1);
        }

        // Опционально: передать размер в байтах как аргумент. Если не указан — генерируем случайный от 1MB до 10GB
        long size;
        if (args.length > 0) {
            size = Long.parseLong(args[0]);
        } else {
            size = randomSizeBytes(1L * 1024L * 1024L, 10L * 1024L * 1024L * 1024L); // 1MB .. 10GB
        }

        String key = "random-upload-" + System.currentTimeMillis() + ".bin";
        System.out.printf("Uploading key=%s size=%,d bytes%n", key, size);

        // Build S3 client
        S3Client s3 = buildS3Client(endpoint, region, accessKey, secretKey);

        try {
            if (size <= MIN_PART_SIZE) {
                uploadSinglePut(s3, bucket, key, size);
            } else {
                multipartUpload(s3, bucket, key, size, DEFAULT_PART_SIZE);
            }
        } catch (Exception e) {
            System.err.println("Ошибка при загрузке: " + e.getMessage());
            e.printStackTrace();
        } finally {
            s3.close();
        }
    }

    private static S3Client buildS3Client(String endpoint, String region, String accessKey, String secretKey) {
        S3Client.Builder builder = S3Client.builder()
                .region(Region.of(region))
                // включаем path-style доступ по умолчанию — полезно для совместимых S3 реализаций
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (accessKey != null && secretKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }

        return builder.build();
    }

    private static void uploadSinglePut(S3Client s3, String bucket, String key, long size) {
        System.out.println("Размер < 5 MiB, выполняется PutObject (один запрос).");

        try (RandomInputStream ris = new RandomInputStream(size)) {
            PutObjectRequest req = PutObjectRequest.builder().bucket(bucket).key(key).build();
            Instant start = Instant.now();
            s3.putObject(req, RequestBody.fromInputStream(ris, size));
            Instant end = Instant.now();
            long millis = Duration.between(start, end).toMillis();
            double speedMBs = (size / 1024.0 / 1024.0) / (millis / 1000.0);
            System.out.printf("PutObject завершён: %,d bytes за %d ms — %.2f MiB/s%n", size, millis, speedMBs);
        } catch (S3Exception e) {
            System.err.println("S3Exception: " + e.awsErrorDetails().errorMessage());
            throw e;
        } catch (IOException e) {
            System.err.println("IOException при чтении генератора случайных данных: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static void multipartUpload(S3Client s3, String bucket, String key, long totalSize, long partSize) throws IOException {
        if (partSize < MIN_PART_SIZE) {
            partSize = MIN_PART_SIZE;
        }
        long numParts = (totalSize + partSize - 1) / partSize;
        System.out.printf("Multipart upload: total=%,d bytes, partSize=%,d, parts=%d%n", totalSize, partSize, numParts);

        CreateMultipartUploadRequest createReq = CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build();
        CreateMultipartUploadResponse createResp = s3.createMultipartUpload(createReq);
        String uploadId = createResp.uploadId();
        System.out.println("Создан multipart upload, uploadId=" + uploadId);

        List<CompletedPart> completedParts = new ArrayList<>();
        long remaining = totalSize;
        int partNumber = 1;

        // используем один RandomInputStream для последовательного чтения частей.
        RandomInputStream rawStream = new RandomInputStream(totalSize);
        // оборачиваем, чтобы части не закрывали underlying поток
        NonCloseableInputStream nonCloseable = new NonCloseableInputStream(rawStream);

        Instant overallStart = Instant.now();
        long totalUploaded = 0L;

        try {
            while (remaining > 0) {
                long thisPartSize = Math.min(partSize, remaining);
                System.out.printf("Uploading part %d (%,d bytes)%n", partNumber, thisPartSize);
                UploadPartRequest upr = UploadPartRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength(thisPartSize)
                        .build();

                Instant partStart = Instant.now();
                UploadPartResponse uprResp = s3.uploadPart(upr, RequestBody.fromInputStream(nonCloseable, thisPartSize));
                Instant partEnd = Instant.now();
                long partMillis = Duration.between(partStart, partEnd).toMillis();
                double partSpeed = (thisPartSize / 1024.0 / 1024.0) / (Math.max(1, partMillis) / 1000.0);

                String etag = uprResp.eTag();
                System.out.printf("Part %d uploaded: etag=%s, time=%d ms, speed=%.2f MiB/s%n", partNumber, etag, partMillis, partSpeed);

                completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
                totalUploaded += thisPartSize;
                remaining -= thisPartSize;
                partNumber++;
            }

            Instant overallEnd = Instant.now();
            long overallMillis = Duration.between(overallStart, overallEnd).toMillis();
            double overallSpeed = (totalUploaded / 1024.0 / 1024.0) / (Math.max(1, overallMillis) / 1000.0);

            // Complete
            CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder()
                    .parts(completedParts)
                    .build();

            CompleteMultipartUploadRequest compReq = CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(completedMultipartUpload)
                    .build();

            s3.completeMultipartUpload(compReq);

            System.out.printf("Multipart upload завершён: total=%,d bytes, time=%d ms, avg speed=%.2f MiB/s%n",
                    totalUploaded, overallMillis, overallSpeed);

        } catch (Exception e) {
            System.err.println("Ошибка во время multipart upload: " + e.getMessage());
            e.printStackTrace();
            // Попытка abort
            try {
                AbortMultipartUploadRequest abortReq = AbortMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .build();
                s3.abortMultipartUpload(abortReq);
                System.err.println("Multipart upload aborted (uploadId=" + uploadId + ")");
            } catch (Exception abortEx) {
                System.err.println("Не удалось abort multipart upload: " + abortEx.getMessage());
                abortEx.printStackTrace();
            }
            throw e;
        } finally {
            // закрываем поток
            nonCloseable.reallyClose();
        }
    }

    private static long randomSizeBytes(long minInclusive, long maxInclusive) {
        Random r = new Random();
        long range = maxInclusive - minInclusive + 1;
        long val;
        if (range <= 0) { // защита от overflow
            val = minInclusive;
        } else {
            val = minInclusive + (Math.abs(r.nextLong()) % range);
        }
        return val;
    }

    // InputStream, который выдаёт псевдослучайные байты заданной общей длины.
    private static class RandomInputStream extends InputStream implements AutoCloseable {
        private final long size;
        private long pos = 0L;
        private final Random rnd = new Random();
        private final byte[] single = new byte[1];

        RandomInputStream(long size) {
            this.size = size;
        }

        @Override
        public int read() throws IOException {
            if (pos >= size) return -1;
            rnd.nextBytes(single);
            pos++;
            return single[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= size) return -1;
            int toRead = (int)Math.min(len, size - pos);
            byte[] tmp = new byte[toRead];
            rnd.nextBytes(tmp);
            System.arraycopy(tmp, 0, b, off, toRead);
            pos += toRead;
            return toRead;
        }

        @Override
        public long skip(long n) throws IOException {
            long k = Math.min(n, size - pos);
            pos += k;
            return k;
        }

        @Override
        public int available() throws IOException {
            long rem = size - pos;
            return rem > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rem;
        }

        @Override
        public void close() throws IOException {
            // ничего
        }
    }

    // Wrapper, чтобы RequestBody.fromInputStream не закрывал underlying поток между частями
    private static class NonCloseableInputStream extends FilterInputStream {
        NonCloseableInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            // не закрываем underlying, real close по требованию
        }

        void reallyClose() {
            try {
                super.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}