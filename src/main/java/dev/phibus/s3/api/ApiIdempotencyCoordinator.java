package dev.phibus.s3.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared idempotency boundary for every automation start command.
 *
 * <p>The key is scoped to the authenticated client and intentionally global across run types:
 * reusing a key for another command is reported as a conflict instead of silently starting work.</p>
 */
@Service
public class ApiIdempotencyCoordinator {
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final ApiIdempotencyStore idempotency;
    private final ObjectMapper objectMapper;

    public ApiIdempotencyCoordinator(ApiIdempotencyStore idempotency, ObjectMapper objectMapper) {
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StartResult start(String idempotencyKey, Object request, String clientId,
                             String resourceType, Function<UUID, UUID> starter) {
        String key = normalizeKey(idempotencyKey);
        if (request == null) throw new IllegalArgumentException("Request body is required");
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("Idempotency resource type is required");
        }

        String normalizedResourceType = resourceType.trim();
        String clientHash = hexDigest(normalizeClient(clientId));
        String keyHash = hexDigest(key);
        String requestHash = requestHash(request);
        UUID proposedRunId = deterministicRunId(normalizedResourceType, clientHash, keyHash);
        ApiIdempotencyStore.Claim claim = idempotency.claim(
                clientHash, keyHash, requestHash, normalizedResourceType, proposedRunId);
        if (claim.replayed()) return new StartResult(claim.runId(), true);

        UUID startedRunId = starter.apply(claim.runId());
        if (!claim.runId().equals(startedRunId)) {
            throw new IllegalStateException("Run service did not preserve the claimed run id");
        }
        return new StartResult(startedRunId, false);
    }

    private String requestHash(Object request) {
        try {
            return hexDigest(objectMapper.writeValueAsBytes(request));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot calculate request fingerprint", error);
        }
    }

    static String normalizeKey(String value) {
        String result = value == null ? "" : value.trim();
        if (!SAFE_KEY.matcher(result).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 1-128 letters, digits, dots, underscores, colons or hyphens");
        }
        return result;
    }

    private static String normalizeClient(String value) {
        return value == null || value.isBlank() ? "anonymous" : value.trim();
    }

    private static UUID deterministicRunId(String resourceType, String clientHash, String keyHash) {
        byte[] bytes = digest((resourceType + ':' + clientHash + ':' + keyHash)
                .getBytes(StandardCharsets.UTF_8));
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static String hexDigest(String value) {
        return hexDigest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexDigest(byte[] value) {
        return HexFormat.of().formatHex(digest(value));
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    public record StartResult(UUID runId, boolean replayed) { }
}
