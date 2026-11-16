package dev.phibus.s3.api;

import dev.phibus.s3.settings.S3ProfileService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class VersionedAutomationApiController {
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed";

    private final S3ProfileService profiles;
    private final IdempotentS3RunService runs;

    public VersionedAutomationApiController(S3ProfileService profiles, IdempotentS3RunService runs) {
        this.profiles = profiles;
        this.runs = runs;
    }

    @GetMapping("/profiles/s3")
    public List<S3ProfileSummary> profiles() {
        return profiles.list().stream()
                .map(profile -> new S3ProfileSummary(profile.id(), profile.name(), profile.defaultProfile()))
                .toList();
    }

    @PostMapping(path = "/runs/s3", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunAccepted> start(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody S3RunRequest request, Principal principal) {
        IdempotentS3RunService.StartResult result = runs.start(
                idempotencyKey, request, principal == null ? null : principal.getName());
        String monitorUrl = "/api/v1/runs/" + result.runId();
        RunAccepted body = new RunAccepted(result.runId(), "S3", "LOCAL", monitorUrl, result.replayed());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, monitorUrl)
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(body);
    }

    public record S3ProfileSummary(UUID id, String name, boolean defaultProfile) { }

    public record RunAccepted(UUID runId, String testType, String scope,
                              String monitorUrl, boolean replayed) { }
}
