package dev.phibus.s3.settings;

import org.springframework.stereotype.Service;

@Service
public class SettingsService {
    private final BootstrapSettingsStore store;
    private final BootstrapSecretCodec codec;
    private final ExternalServiceDiagnostics diagnostics;
    private final ApplicationStateService stateService;

    public SettingsService(BootstrapSettingsStore store, BootstrapSecretCodec codec,
                           ExternalServiceDiagnostics diagnostics, ApplicationStateService stateService) {
        this.store = store;
        this.codec = codec;
        this.diagnostics = diagnostics;
        this.stateService = stateService;
    }

    public BootstrapSettings load() {
        return store.load();
    }

    public BootstrapSettings save(SettingsForm form) {
        BootstrapSettings current = store.load();
        String pgPassword = encryptedOrExisting(form.postgresPassword(), current.postgresql().encryptedPassword());
        String vaultToken = encryptedOrExisting(form.vaultToken(), current.vault().encryptedToken());
        String accessKey = encryptedOrExisting(form.s3AccessKey(), current.s3().encryptedAccessKey());
        String secretKey = encryptedOrExisting(form.s3SecretKey(), current.s3().encryptedSecretKey());

        BootstrapSettings settings = new BootstrapSettings(
                new BootstrapSettings.PostgreSqlSettings(form.jdbcUrl(), form.postgresUsername(), pgPassword),
                new BootstrapSettings.VaultSettings(form.vaultAddress(), vaultToken,
                        blankDefault(form.vaultKvMount(), "secret"), blankDefault(form.vaultSecretPrefix(), "s3-performance"),
                        form.vaultTlsVerify(), form.vaultCaCertificatePath()),
                new BootstrapSettings.S3ProfileSettings(blankDefault(form.s3ProfileName(), "default"), form.s3Endpoint(),
                        blankDefault(form.s3Region(), "us-east-1"), form.s3Bucket(), form.s3PathStyleAccess(),
                        blankDefault(form.s3CredentialsSource(), "VAULT"), form.s3VaultSecretPath(),
                        blankDefault(form.s3AccessKeyField(), "accessKey"), blankDefault(form.s3SecretKeyField(), "secretKey"),
                        accessKey, secretKey));
        store.save(settings);
        stateService.refresh();
        return settings;
    }

    public ExternalServiceDiagnostics.DiagnosticResult testPostgreSql(SettingsForm form) {
        return diagnostics.checkPostgreSql(
                new BootstrapSettings.PostgreSqlSettings(form.jdbcUrl(), form.postgresUsername(), ""),
                form.postgresPassword());
    }

    public ExternalServiceDiagnostics.DiagnosticResult testVault(SettingsForm form) {
        return diagnostics.checkVault(
                new BootstrapSettings.VaultSettings(form.vaultAddress(), "", form.vaultKvMount(),
                        form.vaultSecretPrefix(), form.vaultTlsVerify(), form.vaultCaCertificatePath()),
                form.vaultToken());
    }

    public String bootstrapPath() {
        return store.path().toString();
    }

    public boolean encryptionReady() {
        return codec.available();
    }

    private String encryptedOrExisting(String plain, String existing) {
        return plain == null || plain.isBlank() ? existing : codec.encrypt(plain);
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
