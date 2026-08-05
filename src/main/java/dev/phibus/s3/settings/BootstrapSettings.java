package dev.phibus.s3.settings;

public record BootstrapSettings(
        PostgreSqlSettings postgresql,
        VaultSettings vault,
        S3ProfileSettings s3) {

    public static BootstrapSettings empty() {
        return new BootstrapSettings(PostgreSqlSettings.empty(), VaultSettings.empty(), S3ProfileSettings.empty());
    }

    public record PostgreSqlSettings(String jdbcUrl, String username, String encryptedPassword) {
        public static PostgreSqlSettings empty() {
            return new PostgreSqlSettings("", "", "");
        }

        public boolean configured() {
            return jdbcUrl != null && !jdbcUrl.isBlank() && username != null && !username.isBlank();
        }
    }

    public record VaultSettings(
            String address,
            String encryptedToken,
            String kvMount,
            String secretPrefix,
            boolean tlsVerify,
            String caCertificatePath) {
        public static VaultSettings empty() {
            return new VaultSettings("", "", "secret", "s3-performance", true, "");
        }
    }

    public record S3ProfileSettings(
            String name,
            String endpoint,
            String region,
            String bucket,
            boolean pathStyleAccess,
            String credentialsSource,
            String vaultSecretPath,
            String accessKeyField,
            String secretKeyField,
            String encryptedAccessKey,
            String encryptedSecretKey) {
        public static S3ProfileSettings empty() {
            return new S3ProfileSettings("default", "", "us-east-1", "", true, "VAULT", "", "accessKey", "secretKey", "", "");
        }
    }
}
