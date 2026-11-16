package dev.phibus.s3.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.settings.S3ProfileService;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotentS3RunService {
    private static final String RESOURCE_TYPE = "S3_RUN";
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final ApiIdempotencyStore idempotency;
    private final S3ProfileService profiles;
    private final TestRunService runs;
    private final ObjectMapper objectMapper;

    public IdempotentS3RunService(ApiIdempotencyStore idempotency, S3ProfileService profiles,
                                  TestRunService runs, ObjectMapper objectMapper) {
        this.idempotency = idempotency;
        this.profiles = profiles;
        this.runs = runs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StartResult start(String idempotencyKey, S3RunRequest suppliedRequest, String clientId) {
        String key = normalizeKey(idempotencyKey);
        S3RunRequest request = suppliedRequest == null ? null : suppliedRequest.normalized();
        if (request == null) throw new IllegalArgumentException("Request body is required");

        String clientHash = hexDigest(normalizeClient(clientId));
        String keyHash = hexDigest(key);
        String requestHash = requestHash(request);
        UUID proposedRunId = deterministicRunId(clientHash, keyHash);
        ApiIdempotencyStore.Claim claim = idempotency.claim(
                clientHash, keyHash, requestHash, RESOURCE_TYPE, proposedRunId);
        if (claim.replayed()) return new StartResult(claim.runId(), true);

        requireProfile(request.profileId());
        TestRun run = runs.create(claim.runId(), request.toTestRequest());
        return new StartResult(run.id(), false);
    }

    private void requireProfile(UUID profileId) {
        try {
            profiles.get(profileId);
        } catch (IllegalArgumentException error) {
            throw new ProfileNotFoundException(profileId);
        }
    }

    private String requestHash(S3RunRequest request) {
        try {
            return hexDigest(objectMapper.writeValueAsBytes(request));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot calculate request fingerprint", error);
        }
    }

    private static String normalizeKey(String value) {
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

    private static UUID deterministicRunId(String clientHash, String keyHash) {
        byte[] bytes = digest((RESOURCE_TYPE + ':' + clientHash + ':' + keyHash)
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

    public static final class ProfileNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProfileNotFoundException(UUID id) {
            super("S3 profile not found: " + id);
        }
    }
}
