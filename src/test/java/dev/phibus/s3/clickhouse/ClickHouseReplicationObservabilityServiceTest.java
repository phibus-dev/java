package dev.phibus.s3.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClickHouseReplicationObservabilityServiceTest {
    @Test void criticalWhenReplicaReadonly() throws Exception {
        var replica=new ClickHouseReplicationObservabilityService.Replica("t","ReplicatedMergeTree",false,true,false,0,0,0,0,0,0,0,2,2,"","");
        Method m=ClickHouseReplicationObservabilityService.class.getDeclaredMethod("health",List.class,List.class,List.class);m.setAccessible(true);
        var h=(ClickHouseReplicationObservabilityService.Health)m.invoke(null,List.of(replica),List.of(),List.of());
        assertEquals("CRITICAL",h.status());
    }
    @Test void warningOnReplicationDelay() throws Exception {
        var replica=new ClickHouseReplicationObservabilityService.Replica("t","ReplicatedMergeTree",false,false,false,0,0,0,0,0,0,61,2,2,"","");
        Method m=ClickHouseReplicationObservabilityService.class.getDeclaredMethod("health",List.class,List.class,List.class);m.setAccessible(true);
        var h=(ClickHouseReplicationObservabilityService.Health)m.invoke(null,List.of(replica),List.of(),List.of());
        assertEquals("WARNING",h.status());
    }

    @Test void persistenceFailureDoesNotDiscardCollectedSnapshot() {
        UUID profileId=UUID.randomUUID();
        ClickHouseProfileService profiles=mock(ClickHouseProfileService.class);
        ClickHouseReplicationSnapshotStore history=mock(ClickHouseReplicationSnapshotStore.class);
        ClickHouseReplicationMetrics metrics=mock(ClickHouseReplicationMetrics.class);
        when(profiles.get(profileId)).thenReturn(profile(profileId));
        doThrow(new RuntimeException("PostgreSQL unavailable")).when(history).save(any());

        var snapshot=new ClickHouseReplicationObservabilityService(profiles,history,metrics).snapshot(profileId);

        assertEquals("geo_test",snapshot.profileName());
        verify(metrics).update(snapshot);
    }

    @Test void metricsFailureDoesNotDiscardCollectedSnapshot() {
        UUID profileId=UUID.randomUUID();
        ClickHouseProfileService profiles=mock(ClickHouseProfileService.class);
        ClickHouseReplicationSnapshotStore history=mock(ClickHouseReplicationSnapshotStore.class);
        ClickHouseReplicationMetrics metrics=mock(ClickHouseReplicationMetrics.class);
        when(profiles.get(profileId)).thenReturn(profile(profileId));
        doThrow(new RuntimeException("metrics failure")).when(metrics).update(any());

        var snapshot=new ClickHouseReplicationObservabilityService(profiles,history,metrics).snapshot(profileId);

        assertEquals("geo_test",snapshot.profileName());
        verify(history).save(snapshot);
    }

    private static ClickHouseProfileService.Profile profile(UUID id) {
        return new ClickHouseProfileService.Profile(id,"geo_test",List.of(),"default","default",
                5000,30,false,Instant.now(),Instant.now());
    }
}
