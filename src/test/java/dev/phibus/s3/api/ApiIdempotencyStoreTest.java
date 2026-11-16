package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ApiIdempotencyStoreTest {
    private static final String CLIENT_HASH = "a".repeat(64);
    private static final String KEY_HASH = "b".repeat(64);
    private static final String REQUEST_HASH = "c".repeat(64);

    @Test
    void claimsANewClientKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID runId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ApiIdempotencyStore.Claim claim = new ApiIdempotencyStore(jdbc).claim(
                CLIENT_HASH, KEY_HASH, REQUEST_HASH, "S3_RUN", runId);

        assertThat(claim.runId()).isEqualTo(runId);
        assertThat(claim.replayed()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaysTheOriginalRunForTheSameRequest() throws Exception {
        JdbcTemplate jdbc = existing(REQUEST_HASH, "S3_RUN");
        UUID proposed = UUID.randomUUID();

        ApiIdempotencyStore.Claim claim = new ApiIdempotencyStore(jdbc).claim(
                CLIENT_HASH, KEY_HASH, REQUEST_HASH, "S3_RUN", proposed);

        assertThat(claim.runId()).isNotEqualTo(proposed);
        assertThat(claim.replayed()).isTrue();
    }

    @Test
    void rejectsKeyReuseWithDifferentPayload() throws Exception {
        JdbcTemplate jdbc = existing("d".repeat(64), "S3_RUN");

        assertThatThrownBy(() -> new ApiIdempotencyStore(jdbc).claim(
                CLIENT_HASH, KEY_HASH, REQUEST_HASH, "S3_RUN", UUID.randomUUID()))
                .isInstanceOf(ApiIdempotencyStore.IdempotencyConflictException.class)
                .hasMessageContaining("different request");
    }

    @SuppressWarnings("unchecked")
    private static JdbcTemplate existing(String requestHash, String resourceType) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID existingRunId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            ResultSet result = mock(ResultSet.class);
            when(result.getString("request_hash")).thenReturn(requestHash);
            when(result.getString("resource_type")).thenReturn(resourceType);
            when(result.getObject("run_id", UUID.class)).thenReturn(existingRunId);
            return List.of(mapper.mapRow(result, 0));
        });
        return jdbc;
    }
}
