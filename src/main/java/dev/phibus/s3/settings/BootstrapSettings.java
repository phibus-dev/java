package dev.phibus.s3.settings;

public record BootstrapSettings(
        PostgreSqlSettings postgresql,
        VaultSettings vault,
        S3ProfileSettings s3,
        KeycloakSettings keycloak) {

    public BootstrapSettings {
        postgresql = postgresql == null ? PostgreSqlSettings.empty() : postgresql;
        vault = vault == null ? VaultSettings.empty() : vault;
        s3 = s3 == null ? S3ProfileSettings.empty() : s3;
        keycloak = keycloak == null ? KeycloakSettings.empty() : keycloak;
    }

    public BootstrapSettings(PostgreSqlSettings postgresql, VaultSettings vault, S3ProfileSettings s3) {
        this(postgresql, vault, s3, KeycloakSettings.empty());
    }

    public static BootstrapSettings empty() {
        return new BootstrapSettings(PostgreSqlSettings.empty(), VaultSettings.empty(), S3ProfileSettings.empty(), KeycloakSettings.empty());
    }

    public record PostgreSqlSettings(String jdbcUrl, String username, String encryptedPassword) {
        public static PostgreSqlSettings empty() { return new PostgreSqlSettings("", "", ""); }
        public boolean configured() { return jdbcUrl != null && !jdbcUrl.isBlank() && username != null && !username.isBlank(); }
    }

    public record VaultSettings(
            String address,
            String authMethod,
            String encryptedToken,
            String authMount,
            String roleId,
            String encryptedSecretId,
            String kvMount,
            String secretPrefix,
            boolean tlsVerify,
            String caCertificatePath) {
        public static VaultSettings empty() {
            return new VaultSettings("", "TOKEN", "", "approle", "", "", "secret", "s3-performance", true, "");
        }
        public String normalizedAuthMethod() {
            return authMethod == null || authMethod.isBlank() ? "TOKEN" : authMethod.trim().toUpperCase();
        }
    }

    public record S3ProfileSettings(
            String name, String endpoint, String region, String bucket, boolean pathStyleAccess,
            String credentialsSource, String vaultSecretPath, String accessKeyField, String secretKeyField,
            String encryptedAccessKey, String encryptedSecretKey) {
        public static S3ProfileSettings empty() {
            return new S3ProfileSettings("default", "", "us-east-1", "", true, "VAULT", "", "accessKey", "secretKey", "", "");
        }
    }

    public record KeycloakSettings(
            boolean enabled,
            String issuerUri,
            String clientId,
            String encryptedClientSecret,
            String scopes,
            String roleSource,
            String adminRole,
            String operatorRole,
            String viewerRole) {
        public static KeycloakSettings empty() {
            return new KeycloakSettings(false, "", "", "", "openid,profile,email", "CLIENT", "ADMIN", "OPERATOR", "VIEWER");
        }
        public boolean configured() {
            return !enabled || (issuerUri != null && !issuerUri.isBlank() && clientId != null && !clientId.isBlank());
        }
    }
}
