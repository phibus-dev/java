package dev.phibus.s3.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.stereotype.Service;

@Service
public class VaultAuthService {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long MIN_LEASE_SECONDS = 10;

    private final BootstrapSecretCodec codec;
    private final ObjectMapper objectMapper;
    private final Object tokenLock = new Object();
    private volatile CachedToken cached;

    public VaultAuthService(BootstrapSecretCodec codec, ObjectMapper objectMapper) {
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    public String resolve(BootstrapSettings.VaultSettings settings) {
        return switch (settings.normalizedAuthMethod()) {
            case "TOKEN" -> required(codec.decrypt(settings.encryptedToken()), "Vault token is not configured");
            case "APPROLE" -> resolveAppRole(settings, codec.decrypt(settings.encryptedSecretId()), false);
            default -> throw new IllegalStateException("Unsupported Vault authentication method: " + settings.authMethod());
        };
    }

    public String resolveForTest(BootstrapSettings.VaultSettings settings, String plainToken, String plainSecretId) {
        return switch (settings.normalizedAuthMethod()) {
            case "TOKEN" -> required(plainToken, "Vault token is required for connection test");
            case "APPROLE" -> resolveAppRole(settings, plainSecretId, true);
            default -> throw new IllegalStateException("Unsupported Vault authentication method: " + settings.authMethod());
        };
    }

    public JsonNode readKvV2(BootstrapSettings.VaultSettings settings, String secretPath) {
        String mount = trimSlashes(required(settings.kvMount(), "Vault KV mount is not configured"));
        String path = trimSlashes(required(secretPath, "Vault secret path is not configured"));
        URI uri = URI.create(baseAddress(settings) + "/v1/" + mount + "/data/" + path);
        return authorizedRequest(settings, uri);
    }

    public void invalidate(BootstrapSettings.VaultSettings settings) {
        CachedToken current = cached;
        if (current != null && current.matches(settings)) cached = null;
    }

    private JsonNode authorizedRequest(BootstrapSettings.VaultSettings settings, URI uri) {
        String token = resolve(settings);
        HttpResponse<String> response = send(settings, HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("X-Vault-Token", token).GET().build());
        if ((response.statusCode() == 401 || response.statusCode() == 403)
                && "APPROLE".equals(settings.normalizedAuthMethod())) {
            invalidate(settings);
            token = resolve(settings);
            response = send(settings, HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                    .header("X-Vault-Token", token).GET().build());
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Vault returned HTTP " + response.statusCode());
        }
        try {
            return objectMapper.readTree(response.body()).path("data").path("data");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse Vault KV v2 response", e);
        }
    }

    private String resolveAppRole(BootstrapSettings.VaultSettings settings, String secretId, boolean forceLogin) {
        String roleId = required(settings.roleId(), "Vault AppRole role_id is not configured");
        secretId = required(secretId, "Vault AppRole secret_id is not configured");
        if (!forceLogin) {
            CachedToken current = cached;
            if (current != null && current.matches(settings) && current.usable()) {
                if (!current.renewalDue()) return current.token();
                CachedToken renewed = renew(settings, current);
                if (renewed != null) return renewed.token();
            }
        }
        synchronized (tokenLock) {
            if (!forceLogin) {
                CachedToken current = cached;
                if (current != null && current.matches(settings) && current.usable() && !current.renewalDue()) {
                    return current.token();
                }
            }
            cached = login(settings, roleId, secretId);
            return cached.token();
        }
    }

    private CachedToken login(BootstrapSettings.VaultSettings settings, String roleId, String secretId) {
        String mount = authMount(settings);
        URI uri = URI.create(baseAddress(settings) + "/v1/auth/" + mount + "/login");
        try {
            String body = objectMapper.writeValueAsString(Map.of("role_id", roleId, "secret_id", secretId));
            HttpResponse<String> response = send(settings, HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Vault AppRole login returned HTTP " + response.statusCode());
            }
            JsonNode auth = objectMapper.readTree(response.body()).path("auth");
            String token = required(auth.path("client_token").asText(""),
                    "Vault AppRole response does not contain client_token");
            long leaseSeconds = Math.max(MIN_LEASE_SECONDS, auth.path("lease_duration").asLong(300));
            boolean renewable = auth.path("renewable").asBoolean(false);
            Instant now = Instant.now();
            long skew = Math.max(2, Math.min(30, leaseSeconds / 10));
            return new CachedToken(baseAddress(settings), mount, roleId, token, renewable,
                    now.plusSeconds(Math.max(1, leaseSeconds / 2)),
                    now.plusSeconds(Math.max(1, leaseSeconds - skew)));
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Cannot authenticate to Vault with AppRole", e);
        }
    }

    private CachedToken renew(BootstrapSettings.VaultSettings settings, CachedToken current) {
        if (!current.renewable()) return null;
        synchronized (tokenLock) {
            if (cached != current) return cached != null && cached.usable() ? cached : null;
            URI uri = URI.create(baseAddress(settings) + "/v1/auth/token/renew-self");
            HttpResponse<String> response = send(settings, HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                    .header("X-Vault-Token", current.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build());
            if (response.statusCode() / 100 != 2) {
                cached = null;
                return null;
            }
            try {
                JsonNode auth = objectMapper.readTree(response.body()).path("auth");
                long leaseSeconds = Math.max(MIN_LEASE_SECONDS, auth.path("lease_duration").asLong(300));
                boolean renewable = auth.path("renewable").asBoolean(current.renewable());
                Instant now = Instant.now();
                long skew = Math.max(2, Math.min(30, leaseSeconds / 10));
                cached = new CachedToken(current.address(), current.mount(), current.roleId(), current.token(), renewable,
                        now.plusSeconds(Math.max(1, leaseSeconds / 2)),
                        now.plusSeconds(Math.max(1, leaseSeconds - skew)));
                return cached;
            } catch (Exception e) {
                cached = null;
                return null;
            }
        }
    }

    private HttpResponse<String> send(BootstrapSettings.VaultSettings settings, HttpRequest request) {
        try {
            return httpClient(settings).send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vault request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot connect to Vault", e);
        }
    }

    private static HttpClient httpClient(BootstrapSettings.VaultSettings settings) throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (!settings.tlsVerify()) {
            TrustManager[] trustAll = {new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("");
            builder.sslContext(context).sslParameters(parameters);
        } else if (settings.caCertificatePath() != null && !settings.caCertificatePath().isBlank()) {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate certificate;
            try (InputStream input = Files.newInputStream(Path.of(settings.caCertificatePath()))) {
                certificate = certificateFactory.generateCertificate(input);
            }
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("vault-ca", certificate);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), new SecureRandom());
            builder.sslContext(context);
        }
        return builder.build();
    }

    private static String authMount(BootstrapSettings.VaultSettings settings) {
        return trimSlashes(settings.authMount() == null || settings.authMount().isBlank() ? "approle" : settings.authMount());
    }

    private static String baseAddress(BootstrapSettings.VaultSettings settings) {
        String value = required(settings.address(), "Vault address is not configured").trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalStateException(message);
        return value;
    }

    private static String trimSlashes(String value) { return value.replaceAll("^/+|/+$", ""); }

    private record CachedToken(String address, String mount, String roleId, String token, boolean renewable,
                               Instant renewAt, Instant expiresAt) {
        boolean usable() { return Instant.now().isBefore(expiresAt); }
        boolean renewalDue() { return renewable && !Instant.now().isBefore(renewAt); }
        boolean matches(BootstrapSettings.VaultSettings settings) {
            return address.equals(baseAddress(settings)) && mount.equals(authMount(settings))
                    && roleId.equals(settings.roleId());
        }
    }
}
