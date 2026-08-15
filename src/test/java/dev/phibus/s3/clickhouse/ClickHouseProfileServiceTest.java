package dev.phibus.s3.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ClickHouseProfileServiceTest {
    @Test
    void buildsJdbcUrlForHttpAndHttpsEndpoints() {
        assertEquals("jdbc:clickhouse:http://ch01:8123/default",
                ClickHouseProfileService.jdbcUrl("http://ch01:8123/", "default"));
        assertEquals("jdbc:clickhouse:https://ch01:8443/analytics",
                ClickHouseProfileService.jdbcUrl("https://ch01:8443", "analytics"));
    }

    @Test
    void validatesMultipleEndpointsAndTimeouts() {
        ClickHouseProfileService.validate(new ClickHouseProfileService.ProfileRequest(
                "cluster-a", "http://ch01:8123\nhttps://ch02:8443", "default", "perf", "secret",
                5000, 30, true));
    }

    @Test
    void rejectsNativeEndpointInProfileUntilNativeClientIsImplemented() {
        assertThrows(IllegalArgumentException.class, () -> ClickHouseProfileService.validate(
                new ClickHouseProfileService.ProfileRequest("cluster-a", "tcp://ch01:9000", "default", "perf",
                        "secret", 5000, 30, false)));
    }

    @Test
    void rejectsInvalidTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> ClickHouseProfileService.validate(
                new ClickHouseProfileService.ProfileRequest("cluster-a", "http://ch01:8123", "default", "perf",
                        "secret", 10, 30, false)));
    }
}
