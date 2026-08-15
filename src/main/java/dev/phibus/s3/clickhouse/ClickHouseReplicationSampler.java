package dev.phibus.s3.clickhouse;

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

    public ClickHouseReplicationSampler(ClickHouseProfileService profiles,
                                        ClickHouseReplicationObservabilityService observability) {
        this.profiles = profiles;
        this.observability = observability;
    }

    @Scheduled(fixedDelayString = "${s3perf.clickhouse.replication-sample-interval-ms:10000}", initialDelay = 5000)
    public void sample() {
        for (ClickHouseProfileService.Profile profile : profiles.list()) {
            try {
                observability.snapshot(profile.id());
            } catch (RuntimeException e) {
                LOG.warn("Cannot collect ClickHouse replication snapshot for profile {}: {}", profile.id(), e.getMessage());
            }
        }
    }
}
