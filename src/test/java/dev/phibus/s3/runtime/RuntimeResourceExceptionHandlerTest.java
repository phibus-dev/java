package dev.phibus.s3.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RuntimeResourceExceptionHandlerTest {
    @Test
    void capacityErrorIsReturnedAsRetryableTooManyRequestsResponse() {
        var response = new RuntimeResourceExceptionHandler().capacityExceeded(
                new RuntimeResourceManager.RunCapacityExceededException("capacity exhausted"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(response.getBody()).containsEntry("status", 429)
                .containsEntry("message", "capacity exhausted");
    }

    @Test
    void configuredLimitErrorIsReturnedAsBadRequest() {
        var response = new RuntimeResourceExceptionHandler().limitExceeded(
                new RuntimeResourceManager.RunLimitExceededException("worker limit exceeded"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders()).doesNotContainKey("Retry-After");
        assertThat(response.getBody()).containsEntry("status", 400)
                .containsEntry("message", "worker limit exceeded");
    }
}
