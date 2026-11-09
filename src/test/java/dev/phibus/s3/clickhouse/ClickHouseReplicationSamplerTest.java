package dev.phibus.s3.clickhouse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.phibus.s3.settings.ApplicationStateService;
import org.junit.jupiter.api.Test;

class ClickHouseReplicationSamplerTest {

    @Test
    void skipsDatabaseAccessUntilApplicationIsReady() {
        ClickHouseProfileService profiles = mock(ClickHouseProfileService.class);
        ClickHouseReplicationObservabilityService observability =
                mock(ClickHouseReplicationObservabilityService.class);
        ApplicationStateService stateService = mock(ApplicationStateService.class);
        when(stateService.current()).thenReturn(ApplicationStateService.State.CONFIGURATION_REQUIRED);

        new ClickHouseReplicationSampler(profiles, observability, stateService).sample();

        verifyNoInteractions(profiles, observability);
    }

    @Test
    void databaseOutageDoesNotEscapeScheduledTask() {
        ClickHouseProfileService profiles = mock(ClickHouseProfileService.class);
        ClickHouseReplicationObservabilityService observability =
                mock(ClickHouseReplicationObservabilityService.class);
        ApplicationStateService stateService = mock(ApplicationStateService.class);
        when(stateService.current()).thenReturn(ApplicationStateService.State.READY);
        when(profiles.list()).thenThrow(new IllegalStateException("database unavailable"));
        ClickHouseReplicationSampler sampler =
                new ClickHouseReplicationSampler(profiles, observability, stateService);

        assertDoesNotThrow(sampler::sample);
    }
}
