package dev.phibus.s3.settings;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("externalServices")
public class ExternalServicesHealthIndicator implements HealthIndicator {
    private final ApplicationStateService stateService;

    public ExternalServicesHealthIndicator(ApplicationStateService stateService) {
        this.stateService = stateService;
    }

    @Override
    public Health health() {
        ApplicationStateService.State state = stateService.current();
        Health.Builder builder = state == ApplicationStateService.State.READY ? Health.up() : Health.down();
        return builder.withDetail("applicationState", state)
                .withDetail("settings", "/settings")
                .build();
    }
}
