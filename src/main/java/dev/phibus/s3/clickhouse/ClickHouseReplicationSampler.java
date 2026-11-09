package dev.phibus.s3.clickhouse;

import dev.phibus.s3.settings.ApplicationStateService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseReplicationSampler {
    private static final Logger LOG = LoggerFactory.getLogger(ClickHouseReplicationSampler.class);
    private final ClickHouseProfileService profiles;
    private final ClickHouseReplicationObservabilityService observability;
    private final ApplicationStateService stateService;

    public ClickHouseReplicationSampler(ClickHouseProfileService profiles,
                                        ClickHouseReplicationObservabilityService observability,
                                        ApplicationStateService stateService) {
        this.profiles = profiles;
        this.observability = observability;
        this.stateService = stateService;
    }

    @Scheduled(fixedDelayString = "${s3perf.clickhouse.replication-sample-interval-ms:10000}", initialDelay = 5000)
    public void sample() {
        if (stateService.current() != ApplicationStateService.State.READY) return;
        final List<ClickHouseProfileService.Profile> configuredProfiles;
        try {
            configuredProfiles = profiles.list();
        } catch (RuntimeException e) {
            LOG.warn("Cannot list ClickHouse profiles for replication sampling: {}", e.getMessage());
            return;
        }
        for (ClickHouseProfileService.Profile profile : configuredProfiles) {
            try {
                observability.snapshot(profile.id());
            } catch (RuntimeException e) {
                LOG.warn("Cannot collect ClickHouse replication snapshot for profile {}: {}",
                        profile.id(), e.getMessage());
            }
        }
    }
}
