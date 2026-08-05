package dev.phibus.s3.diagnostics;

import dev.phibus.s3.credentials.CredentialProvider;
import dev.phibus.s3.credentials.S3Credentials;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.ExternalServiceDiagnostics;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.test.TestRequest;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Service
public class SystemDiagnosticsService {
    private final SettingsService settingsService;
    private final ExternalServiceDiagnostics externalDiagnostics;
    private final CredentialProvider credentialProvider;

    public SystemDiagnosticsService(SettingsService settingsService,
                                    ExternalServiceDiagnostics externalDiagnostics,
                                    CredentialProvider credentialProvider) {
        this.settingsService = settingsService;
        this.externalDiagnostics = externalDiagnostics;
        this.credentialProvider = credentialProvider;
    }

    public SystemReport inspect() {
        BootstrapSettings settings = settingsService.load();
        var postgres = externalDiagnostics.checkPostgreSql(settings.postgresql(),
                externalDiagnostics.decrypt(settings.postgresql().encryptedPassword()));
        var vault = externalDiagnostics.checkVault(settings.vault(),
                externalDiagnostics.decrypt(settings.vault().encryptedToken()));
        return new SystemReport(postgres, vault, checkS3(settings), applicationDetails());
    }

    private ServiceStatus checkS3(BootstrapSettings settings) {
        long started = System.nanoTime();
        BootstrapSettings.S3ProfileSettings profile = settings.s3();
        if (profile.endpoint() == null || profile.endpoint().isBlank()) {
            return ServiceStatus.failed("S3", 0, "S3 endpoint is not configured");
        }
        try {
            TestRequest request = new TestRequest(profile.endpoint(), profile.bucket(), profile.region(), null, null,
                    profile.pathStyleAccess(), "diagnostics/probe", 1, 5, 1, 1, true, "UPLOAD");
            S3Credentials credentials = credentialProvider.resolve(request);
            try (S3Client client = S3Client.builder().endpointOverride(URI.create(profile.endpoint()))
                    .region(Region.of(profile.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(credentials.accessKey(), credentials.secretKey())))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(profile.pathStyleAccess()).build()).build()) {
                List<String> buckets = client.listBuckets().buckets().stream().map(bucket -> bucket.name()).sorted().toList();
                Map<String, String> details = new LinkedHashMap<>();
                details.put("endpoint", profile.endpoint());
                details.put("region", profile.region());
                details.put("bucketCount", Integer.toString(buckets.size()));
                details.put("configuredBucket", profile.bucket() == null ? "" : profile.bucket());
                return ServiceStatus.ok("S3", elapsed(started), details);
            }
        } catch (Exception e) {
            return ServiceStatus.failed("S3", elapsed(started), rootMessage(e));
        }
    }

    private static Map<String, String> applicationDetails() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("uptime", Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()).toString());
        details.put("javaVersion", System.getProperty("java.version"));
        details.put("processors", Integer.toString(runtime.availableProcessors()));
        details.put("heapUsedMiB", Long.toString((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024));
        details.put("heapMaxMiB", Long.toString(runtime.maxMemory() / 1024 / 1024));
        details.put("threads", Integer.toString(ManagementFactory.getThreadMXBean().getThreadCount()));
        return details;
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record SystemReport(ExternalServiceDiagnostics.DiagnosticResult postgresql,
                               ExternalServiceDiagnostics.DiagnosticResult vault,
                               ServiceStatus s3,
                               Map<String, String> application) { }

    public record ServiceStatus(String service, boolean success, long latencyMs,
                                String message, Map<String, String> details) {
        static ServiceStatus ok(String service, long latencyMs, Map<String, String> details) {
            return new ServiceStatus(service, true, latencyMs, "OK", details);
        }
        static ServiceStatus failed(String service, long latencyMs, String message) {
            return new ServiceStatus(service, false, latencyMs, message, Map.of());
        }
    }
}
