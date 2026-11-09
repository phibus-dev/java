package dev.phibus.s3.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import dev.phibus.s3.distributed.ApplicationMode;
import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.S3ProfileService;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.settings.VaultAuthService;
import dev.phibus.s3.test.TestRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredCredentialProvider implements CredentialProvider {
    private final SettingsService settingsService;
    private final BootstrapSecretCodec codec;
    private final VaultAuthService vaultAuthService;
    private final S3ProfileService profileService;
    private final ApplicationMode applicationMode;

    public ConfiguredCredentialProvider(SettingsService settingsService, BootstrapSecretCodec codec,
                                        com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                        VaultAuthService vaultAuthService, S3ProfileService profileService,
                                        @Value("${s3perf.application-mode:COORDINATOR}") String applicationMode) {
        this.settingsService = settingsService;
        this.codec = codec;
        this.vaultAuthService = vaultAuthService;
        this.profileService = profileService;
        this.applicationMode = ApplicationMode.from(applicationMode);
    }

    @Override
    public S3Credentials resolve(TestRequest request) {
        if (notBlank(request.accessKey()) && notBlank(request.secretKey()))
            return new S3Credentials(request.accessKey(), request.secretKey());
        BootstrapSettings settings = settingsService.load();
        S3ProfileService.Profile selected = applicationMode == ApplicationMode.AGENT ? null
                : request.profileId() == null ? profileService.defaultProfile() : profileService.get(request.profileId());
        if (selected != null) return resolveProfile(settings.vault(), selected);
        BootstrapSettings.S3ProfileSettings profile = settings.s3();
        String source = normalizedSource(profile.credentialsSource());
        return switch (source) {
            case "MANUAL" -> new S3Credentials(codec.decrypt(profile.encryptedAccessKey()), codec.decrypt(profile.encryptedSecretKey()));
            case "ENVIRONMENT" -> environmentCredentials();
            case "VAULT" -> fromVault(settings.vault(), profile.vaultSecretPath(), profile.accessKeyField(),
                    profile.secretKeyField(), "sessionToken");
            default -> throw new IllegalStateException("Unsupported S3 credentials source: " + source);
        };
    }

    private S3Credentials resolveProfile(BootstrapSettings.VaultSettings vault, S3ProfileService.Profile profile) {
        return switch (normalizedSource(profile.credentialsSource())) {
            case "ENVIRONMENT" -> environmentCredentials();
            case "VAULT" -> fromVault(vault, profile.vaultSecretPath(), profile.accessKeyField(),
                    profile.secretKeyField(), profile.sessionTokenField());
            case "MANUAL" -> throw new IllegalStateException(
                    "MANUAL profile requires accessKey and secretKey in the test request; secrets are not stored in PostgreSQL");
            default -> throw new IllegalStateException("Unsupported S3 credentials source: " + profile.credentialsSource());
        };
    }

    private S3Credentials fromVault(BootstrapSettings.VaultSettings vault, String secretPath,
                                    String accessKeyField, String secretKeyField, String sessionTokenField) {
        JsonNode data = vaultAuthService.readKvV2(vault, secretPath);
        String accessKey = data.path(defaultValue(accessKeyField, "accessKey")).asText("");
        String secretKey = data.path(defaultValue(secretKeyField, "secretKey")).asText("");
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("Vault secret does not contain configured S3 credential fields");
        }
        String sessionToken = sessionTokenField == null || sessionTokenField.isBlank()
                ? null : data.path(sessionTokenField).asText(null);
        return new S3Credentials(accessKey, secretKey, sessionToken);
    }

    private static S3Credentials environmentCredentials() {
        return new S3Credentials(requiredEnv("AWS_ACCESS_KEY_ID"), requiredEnv("AWS_SECRET_ACCESS_KEY"),
                System.getenv("AWS_SESSION_TOKEN"));
    }
    private static String normalizedSource(String value) { return defaultValue(value, "VAULT").toUpperCase(); }
    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Environment variable " + name + " is not set");
        return value;
    }
    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
}
