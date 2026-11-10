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
}
