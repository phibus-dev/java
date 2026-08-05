package dev.phibus.s3.settings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExternalServiceDiagnostics {
    private final BootstrapSecretCodec codec;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ExternalServiceDiagnostics(BootstrapSecretCodec codec) {
        this.codec = codec;
    }

    public DiagnosticResult checkPostgreSql(BootstrapSettings.PostgreSqlSettings settings, String plainPassword) {
        long started = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(settings.jdbcUrl(), settings.username(), plainPassword);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select version()")) {
            result.next();
            return DiagnosticResult.ok("PostgreSQL", elapsed(started), Map.of("version", result.getString(1)));
        } catch (Exception e) {
            return DiagnosticResult.failed("PostgreSQL", elapsed(started), safeMessage(e));
        }
    }

    public DiagnosticResult checkVault(BootstrapSettings.VaultSettings settings, String plainToken) {
        long started = System.nanoTime();
        if (settings.address() == null || settings.address().isBlank()) {
            return DiagnosticResult.failed("Vault", 0, "Vault address is not configured");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(settings.address().replaceAll("/$", "") + "/v1/sys/health"))
                    .timeout(Duration.ofSeconds(8)).GET();
            if (plainToken != null && !plainToken.isBlank()) {
                builder.header("X-Vault-Token", plainToken);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> details = new LinkedHashMap<>();
            details.put("httpStatus", Integer.toString(response.statusCode()));
            details.put("supportedVersion", "HashiCorp Vault Community Edition 2.0.x");
            boolean ok = response.statusCode() == 200 || response.statusCode() == 429 || response.statusCode() == 472
                    || response.statusCode() == 473 || response.statusCode() == 501 || response.statusCode() == 503;
            return ok ? DiagnosticResult.ok("Vault", elapsed(started), details)
                    : DiagnosticResult.failed("Vault", elapsed(started), "HTTP " + response.statusCode());
        } catch (Exception e) {
            return DiagnosticResult.failed("Vault", elapsed(started), safeMessage(e));
        }
    }

    public String decrypt(String encrypted) {
        return encrypted == null || encrypted.isBlank() ? "" : codec.decrypt(encrypted);
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public record DiagnosticResult(String service, boolean success, long latencyMs, String message, Map<String, String> details) {
        static DiagnosticResult ok(String service, long latencyMs, Map<String, String> details) {
            return new DiagnosticResult(service, true, latencyMs, "OK", details);
        }
        static DiagnosticResult failed(String service, long latencyMs, String message) {
            return new DiagnosticResult(service, false, latencyMs, message, Map.of());
        }
    }
}
