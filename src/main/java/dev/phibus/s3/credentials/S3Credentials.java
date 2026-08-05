package dev.phibus.s3.credentials;

public record S3Credentials(String accessKey, String secretKey) {
    public S3Credentials {
        if (accessKey == null || accessKey.isBlank()) throw new IllegalArgumentException("S3 access key is empty");
        if (secretKey == null || secretKey.isBlank()) throw new IllegalArgumentException("S3 secret key is empty");
    }
}
