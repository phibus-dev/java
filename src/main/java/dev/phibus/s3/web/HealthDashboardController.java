package dev.phibus.s3.web;

import dev.phibus.s3.runtime.RuntimeResourceManager;
import dev.phibus.s3.settings.ApplicationStateService;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.SettingsService;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HealthDashboardController {
    private final SettingsService settingsService;
    private final ApplicationStateService stateService;
    private final RuntimeResourceManager resources;

    public HealthDashboardController(SettingsService settingsService, ApplicationStateService stateService,
                                     RuntimeResourceManager resources) {
        this.settingsService = settingsService;
        this.stateService = stateService;
        this.resources = resources;
    }

    @GetMapping("/monitoring")
    public String monitoring() {
        return "monitoring";
    }

    @GetMapping("/api/health/overview")
    @ResponseBody
    public HealthOverview overview() {
        BootstrapSettings settings = settingsService.load();
        List<ComponentHealth> components = List.of(
                component("PostgreSQL", settings.postgresql().configured(), settings.postgresql().jdbcUrl(),
                        "Внешняя база данных приложения"),
                component("Vault", configured(settings.vault().address()), settings.vault().address(),
                        "Хранилище секретов, auth: " + settings.vault().normalizedAuthMethod()),
                component("S3", configured(settings.s3().endpoint()), settings.s3().endpoint(),
                        configured(settings.s3().bucket()) ? "Bucket: " + settings.s3().bucket() : "Bucket не закреплён"),
                component("Keycloak", settings.keycloak().enabled() && settings.keycloak().configured(),
                        settings.keycloak().issuerUri(), settings.keycloak().enabled() ? "Включён" : "Отключён"),
                new ComponentHealth("Приложение", "UP", "local", "Spring Boot process", null));
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        return new HealthOverview(stateService.current().name(), Instant.now(), uptimeMillis,
                resources.capacity(), components);
    }

    private ComponentHealth component(String name, boolean ready, String target, String details) {
        return new ComponentHealth(name, ready ? "READY" : "NOT_CONFIGURED", safe(target), details, null);
    }

    private boolean configured(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    public record HealthOverview(String applicationState, Instant checkedAt, long uptimeMillis,
                                 RuntimeResourceManager.CapacitySnapshot runtimeCapacity,
                                 List<ComponentHealth> components) {}

    public record ComponentHealth(String name, String status, String target, String details, Long latencyMs) {}
}
