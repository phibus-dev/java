package dev.phibus.s3.settings;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class ApplicationStateService {
    public enum State { CONFIGURATION_REQUIRED, READY, DEGRADED }

    private final BootstrapSettingsStore store;
    private final ExternalServiceDiagnostics diagnostics;
    private volatile State cachedState = State.CONFIGURATION_REQUIRED;
    private volatile Instant checkedAt = Instant.EPOCH;

    public ApplicationStateService(BootstrapSettingsStore store, ExternalServiceDiagnostics diagnostics) {
        this.store = store;
        this.diagnostics = diagnostics;
    }

    public State current() {
        if (Duration.between(checkedAt, Instant.now()).compareTo(Duration.ofSeconds(10)) > 0) {
            refresh();
        }
        return cachedState;
    }

    public synchronized State refresh() {
        BootstrapSettings settings = store.load();
        if (!settings.postgresql().configured()) {
            cachedState = State.CONFIGURATION_REQUIRED;
        } else {
            String password;
            try {
                password = diagnostics.decrypt(settings.postgresql().encryptedPassword());
            } catch (RuntimeException e) {
                cachedState = State.DEGRADED;
                checkedAt = Instant.now();
                return cachedState;
            }
            cachedState = diagnostics.checkPostgreSql(settings.postgresql(), password).success()
                    ? State.READY : State.DEGRADED;
        }
        checkedAt = Instant.now();
        return cachedState;
    }
}
