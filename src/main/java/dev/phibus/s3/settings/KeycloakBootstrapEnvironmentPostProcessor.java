package dev.phibus.s3.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class KeycloakBootstrapEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path path = Path.of(value(System.getenv("S3_PERF_BOOTSTRAP_FILE"), "config/bootstrap-settings.json"))
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            BootstrapSettings settings = new ObjectMapper().readValue(json, BootstrapSettings.class);
            BootstrapSettings.KeycloakSettings keycloak = settings.keycloak();
            if (keycloak == null || !keycloak.enabled() || !keycloak.configured()) {
                return;
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("s3perf.security.enabled", "true");
            properties.put("spring.security.oauth2.client.provider.keycloak.issuer-uri", keycloak.issuerUri());
            properties.put("spring.security.oauth2.client.registration.keycloak.client-id", keycloak.clientId());
            properties.put("spring.security.oauth2.client.registration.keycloak.authorization-grant-type", "authorization_code");
            properties.put("spring.security.oauth2.client.registration.keycloak.scope", keycloak.scopes());
            properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", keycloak.issuerUri());
            String secret = decrypt(keycloak.encryptedClientSecret());
            if (!secret.isBlank()) {
                properties.put("spring.security.oauth2.client.registration.keycloak.client-secret", secret);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("keycloakBootstrap", properties));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot apply Keycloak bootstrap settings from " + path
                    + ". Verify that the file contains valid JSON and that S3_PERF_BOOTSTRAP_KEY has not changed.", e);
        }
    }

    private static String decrypt(String encoded) throws Exception {
        if (encoded == null || encoded.isBlank()) {
            return "";
        }
        String passphrase = System.getenv("S3_PERF_BOOTSTRAP_KEY");
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalStateException("S3_PERF_BOOTSTRAP_KEY is required to decrypt Keycloak client secret");
        }
        byte[] key = MessageDigest.getInstance("SHA-256").digest(passphrase.getBytes(StandardCharsets.UTF_8));
        byte[] combined = Base64.getDecoder().decode(encoded);
        if (combined.length <= 12) {
            throw new IllegalStateException("Encrypted Keycloak client secret has an invalid format");
        }
        byte[] iv = java.util.Arrays.copyOfRange(combined, 0, 12);
        byte[] encrypted = java.util.Arrays.copyOfRange(combined, 12, combined.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
