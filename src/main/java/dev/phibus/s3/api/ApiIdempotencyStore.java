package dev.phibus.s3.api;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApiIdempotencyStore {
    private final JdbcTemplate jdbc;

    public ApiIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Claim claim(String clientHash, String keyHash, String requestHash,
                       String resourceType, UUID proposedRunId) {
        int inserted = jdbc.update("""
                INSERT INTO api_idempotency(client_hash, idempotency_key_hash, request_hash,
                    resource_type, run_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (client_hash, idempotency_key_hash) DO NOTHING
                """, clientHash, keyHash, requestHash, resourceType, proposedRunId);
        if (inserted == 1) return new Claim(proposedRunId, false);

        List<Entry> entries = jdbc.query("""
                SELECT request_hash, resource_type, run_id
                  FROM api_idempotency
                 WHERE client_hash = ? AND idempotency_key_hash = ?
                """, (result, row) -> new Entry(result.getString("request_hash").trim(),
                result.getString("resource_type"), result.getObject("run_id", UUID.class)),
                clientHash, keyHash);
        if (entries.isEmpty()) {
            throw new IllegalStateException("Idempotency claim disappeared during concurrent processing");
        }
        Entry existing = entries.getFirst();
        if (!existing.requestHash().equals(requestHash) || !existing.resourceType().equals(resourceType)) {
            throw new IdempotencyConflictException();
        }
        return new Claim(existing.runId(), true);
    }

    private record Entry(String requestHash, String resourceType, UUID runId) { }

    public record Claim(UUID runId, boolean replayed) { }

    public static final class IdempotencyConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IdempotencyConflictException() {
            super("Idempotency key was already used with a different request");
        }
    }
}
