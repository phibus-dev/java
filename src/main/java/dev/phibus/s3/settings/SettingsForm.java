package dev.phibus.s3.settings;

import jakarta.validation.constraints.NotBlank;

public record SettingsForm(
        @NotBlank String jdbcUrl,
        @NotBlank String postgresUsername,
        String postgresPassword,
        String vaultAddress,
        String vaultToken,
        String vaultKvMount,
        String vaultSecretPrefix,
        boolean vaultTlsVerify,
        String vaultCaCertificatePath,
        String s3ProfileName,
        String s3Endpoint,
        String s3Region,
        String s3Bucket,
        boolean s3PathStyleAccess,
        String s3CredentialsSource,
        String s3VaultSecretPath,
        String s3AccessKeyField,
        String s3SecretKeyField,
        String s3AccessKey,
        String s3SecretKey) {
}
