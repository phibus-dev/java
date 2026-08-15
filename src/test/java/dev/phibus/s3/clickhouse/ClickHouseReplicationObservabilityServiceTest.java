package dev.phibus.s3.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.lang.reflect.Method;
import java.util.List;
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
}
