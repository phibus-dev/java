package dev.phibus.s3.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.settings.VaultAuthService;
import dev.phibus.s3.test.TestRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredCredentialProvider implements CredentialProvider {
    private final SettingsService settingsService;
    private final BootstrapSecretCodec codec;
    private final ObjectMapper objectMapper;
    private final VaultAuthService vaultAuthService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ConfiguredCredentialProvider(SettingsService settingsService, BootstrapSecretCodec codec,
                                        ObjectMapper objectMapper, VaultAuthService vaultAuthService) {
        this.settingsService = settingsService;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.vaultAuthService = vaultAuthService;
    }

    @Override
    public S3Credentials resolve(TestRequest request) {
        if (notBlank(request.accessKey()) && notBlank(request.secretKey())) return new S3Credentials(request.accessKey(), request.secretKey());
        BootstrapSettings settings = settingsService.load();
        BootstrapSettings.S3ProfileSettings profile = settings.s3();
        String source = profile.credentialsSource() == null ? "VAULT" : profile.credentialsSource().trim().toUpperCase();
        return switch (source) {
            case "MANUAL" -> new S3Credentials(codec.decrypt(profile.encryptedAccessKey()), codec.decrypt(profile.encryptedSecretKey()));
            case "ENVIRONMENT" -> new S3Credentials(requiredEnv("AWS_ACCESS_KEY_ID"), requiredEnv("AWS_SECRET_ACCESS_KEY"));
            case "VAULT" -> fromVault(settings.vault(), profile);
            default -> throw new IllegalStateException("Unsupported S3 credentials source: " + source);
        };
    }

    private S3Credentials fromVault(BootstrapSettings.VaultSettings vault, BootstrapSettings.S3ProfileSettings profile) {
        if (vault.address() == null || vault.address().isBlank()) throw new IllegalStateException("Vault address is not configured");
        if (profile.vaultSecretPath() == null || profile.vaultSecretPath().isBlank()) throw new IllegalStateException("Vault S3 secret path is not configured");
        String token = vaultAuthService.resolve(vault);
        String mount = trimSlashes(vault.kvMount());
        String path = trimSlashes(profile.vaultSecretPath());
        URI uri = URI.create(stripTrailingSlash(vault.address()) + "/v1/" + mount + "/data/" + path);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("X-Vault-Token", token).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Vault returned HTTP " + response.statusCode());
            JsonNode data = objectMapper.readTree(response.body()).path("data").path("data");
            return new S3Credentials(data.path(profile.accessKeyField()).asText(""), data.path(profile.secretKeyField()).asText(""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vault request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read S3 credentials from Vault: " + e.getMessage(), e);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Environment variable " + name + " is not set");
        return value;
    }
    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private static String stripTrailingSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private static String trimSlashes(String value) { return value == null ? "" : value.replaceAll("^/+|/+$", ""); }
}
