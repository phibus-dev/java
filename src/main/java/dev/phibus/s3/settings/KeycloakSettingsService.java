package dev.phibus.s3.settings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Service;

@Service
public class KeycloakSettingsService {
    private final BootstrapSettingsStore store;
    private final BootstrapSecretCodec codec;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public KeycloakSettingsService(BootstrapSettingsStore store, BootstrapSecretCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    public BootstrapSettings.KeycloakSettings load() {
        return store.load().keycloak();
    }

    public BootstrapSettings.KeycloakSettings save(KeycloakSettingsForm form) {
        BootstrapSettings current = store.load();
        String encryptedSecret = form.clientSecret() == null || form.clientSecret().isBlank()
                ? current.keycloak().encryptedClientSecret() : codec.encrypt(form.clientSecret());
        BootstrapSettings.KeycloakSettings keycloak = new BootstrapSettings.KeycloakSettings(
                form.enabled(), trim(form.issuerUri()), trim(form.clientId()), encryptedSecret,
                defaultValue(form.scopes(), "openid,profile,email"),
                defaultValue(form.roleSource(), "REALM"),
                defaultValue(form.adminRole(), "ADMIN"),
                defaultValue(form.operatorRole(), "OPERATOR"),
                defaultValue(form.viewerRole(), "VIEWER"));
        if (!keycloak.configured()) {
            throw new IllegalArgumentException("Для включения Keycloak необходимо указать Issuer URI и Client ID");
        }
        store.save(new BootstrapSettings(current.postgresql(), current.vault(), current.s3(), keycloak));
        return keycloak;
    }

    public ConnectionTest test(KeycloakSettingsForm form) {
        String issuer = trim(form.issuerUri());
        if (issuer.isBlank()) {
            return new ConnectionTest(false, "Issuer URI не указан", 0);
        }
        String url = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300
                    && response.body().contains("authorization_endpoint") && response.body().contains("jwks_uri");
            return new ConnectionTest(ok, ok ? "OIDC discovery доступен" : "Некорректный OIDC discovery response: HTTP " + response.statusCode(), latency);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return new ConnectionTest(false, e.getMessage(), Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }

    public record KeycloakSettingsForm(boolean enabled, String issuerUri, String clientId, String clientSecret,
                                       String scopes, String roleSource, String adminRole,
                                       String operatorRole, String viewerRole) { }
    public record ConnectionTest(boolean success, String message, long latencyMs) { }
}
