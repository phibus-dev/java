package dev.phibus.s3.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class VaultAuthService {
    private final BootstrapSecretCodec codec;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile CachedToken cached;

    public VaultAuthService(BootstrapSecretCodec codec, ObjectMapper objectMapper) {
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    public String resolve(BootstrapSettings.VaultSettings settings) {
        return switch (settings.normalizedAuthMethod()) {
            case "TOKEN" -> required(codec.decrypt(settings.encryptedToken()), "Vault token is not configured");
            case "APPROLE" -> resolveAppRole(settings, codec.decrypt(settings.encryptedSecretId()));
            default -> throw new IllegalStateException("Unsupported Vault authentication method: " + settings.authMethod());
        };
    }

    public String resolveForTest(BootstrapSettings.VaultSettings settings, String plainToken, String plainSecretId) {
        return switch (settings.normalizedAuthMethod()) {
            case "TOKEN" -> required(plainToken, "Vault token is required for connection test");
            case "APPROLE" -> resolveAppRole(settings, plainSecretId);
            default -> throw new IllegalStateException("Unsupported Vault authentication method: " + settings.authMethod());
        };
    }

    private String resolveAppRole(BootstrapSettings.VaultSettings settings, String secretId) {
        String roleId = required(settings.roleId(), "Vault AppRole role_id is not configured");
        secretId = required(secretId, "Vault AppRole secret_id is not configured");
        CachedToken current = cached;
        if (current != null && current.matches(settings.address(), settings.authMount(), roleId) && current.valid()) {
            return current.token();
        }
        String mount = trimSlashes(settings.authMount() == null || settings.authMount().isBlank() ? "approle" : settings.authMount());
        URI uri = URI.create(stripTrailingSlash(settings.address()) + "/v1/auth/" + mount + "/login");
        try {
            String body = objectMapper.writeValueAsString(Map.of("role_id", roleId, "secret_id", secretId));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Vault AppRole login returned HTTP " + response.statusCode());
            }
            JsonNode auth = objectMapper.readTree(response.body()).path("auth");
            String token = required(auth.path("client_token").asText(""), "Vault AppRole response does not contain client_token");
            long leaseSeconds = Math.max(30, auth.path("lease_duration").asLong(300));
            cached = new CachedToken(settings.address(), mount, roleId, token,
                    Instant.now().plusSeconds(Math.max(15, leaseSeconds - 30)));
            return token;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vault AppRole login interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot authenticate to Vault with AppRole: " + e.getMessage(), e);
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalStateException(message);
        return value;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Vault address is not configured");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String trimSlashes(String value) { return value.replaceAll("^/+|/+$", ""); }

    private record CachedToken(String address, String mount, String roleId, String token, Instant expiresAt) {
        boolean valid() { return Instant.now().isBefore(expiresAt); }
        boolean matches(String address, String mount, String roleId) {
            return this.address.equals(address) && this.mount.equals(trimSlashes(mount == null || mount.isBlank() ? "approle" : mount))
                    && this.roleId.equals(roleId);
        }
    }
}
