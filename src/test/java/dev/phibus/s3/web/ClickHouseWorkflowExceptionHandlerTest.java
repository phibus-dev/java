package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClickHouseWorkflowExceptionHandlerTest {
    private final ClickHouseWorkflowExceptionHandler handler = new ClickHouseWorkflowExceptionHandler();

    @Test
    void exposesRootCauseForWorkflowStartFailure() {
        var response = handler.unavailable(new IllegalStateException("wrapper",
                new RuntimeException("clickhouse_failover_run is unavailable")));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("message", "clickhouse_failover_run is unavailable");
    }

    @Test
    void returnsBadRequestForInvalidWorkflowInput() {
        var response = handler.badRequest(new IllegalArgumentException("Invalid ClickHouse table name"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", "Invalid ClickHouse table name");
    }
}
