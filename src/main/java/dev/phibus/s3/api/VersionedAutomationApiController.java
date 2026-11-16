package dev.phibus.s3.api;

import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.kafka.KafkaProfileService;
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

    private final S3ProfileService s3Profiles;
    private final ClickHouseProfileService clickHouseProfiles;
    private final KafkaProfileService kafkaProfiles;
    private final IdempotentS3RunService s3Runs;
    private final IdempotentAutomationRunService automationRuns;

    public VersionedAutomationApiController(S3ProfileService s3Profiles,
                                             ClickHouseProfileService clickHouseProfiles,
                                             KafkaProfileService kafkaProfiles,
                                             IdempotentS3RunService s3Runs,
                                             IdempotentAutomationRunService automationRuns) {
        this.s3Profiles = s3Profiles;
        this.clickHouseProfiles = clickHouseProfiles;
        this.kafkaProfiles = kafkaProfiles;
        this.s3Runs = s3Runs;
        this.automationRuns = automationRuns;
    }

    @GetMapping("/profiles/s3")
    public List<ProfileSummary> s3Profiles() {
        return s3Profiles.list().stream()
                .map(profile -> new ProfileSummary(profile.id(), profile.name(), profile.defaultProfile()))
                .toList();
    }

    @GetMapping("/profiles/clickhouse")
    public List<ProfileSummary> clickHouseProfiles() {
        return clickHouseProfiles.list().stream()
                .map(profile -> new ProfileSummary(profile.id(), profile.name(), profile.defaultProfile()))
                .toList();
    }

    @GetMapping("/profiles/kafka")
    public List<ProfileSummary> kafkaProfiles() {
        return kafkaProfiles.list().stream()
                .map(profile -> new ProfileSummary(profile.id(), profile.name(), profile.defaultProfile()))
                .toList();
    }

    @PostMapping(path = "/runs/s3", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunAccepted> startS3(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody S3RunRequest request, Principal principal) {
        IdempotentS3RunService.StartResult result = s3Runs.start(
                idempotencyKey, request, clientId(principal));
        return accepted(result.runId(), "S3", "LOCAL", result.replayed());
    }

    @PostMapping(path = "/runs/clickhouse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunAccepted> startClickHouse(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ClickHouseTestRequest request, Principal principal) {
        ApiIdempotencyCoordinator.StartResult result = automationRuns.startClickHouse(
                idempotencyKey, request, clientId(principal));
        return accepted(result.runId(), "CLICKHOUSE", "LOCAL", result.replayed());
    }

    @PostMapping(path = "/runs/kafka", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunAccepted> startKafka(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody KafkaProducerRunService.ProducerRequest request, Principal principal) {
        ApiIdempotencyCoordinator.StartResult result = automationRuns.startKafka(
                idempotencyKey, request, clientId(principal));
        return accepted(result.runId(), "KAFKA", "LOCAL", result.replayed());
    }

    @PostMapping(path = "/runs/distributed/s3", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunAccepted> startDistributedS3(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody IdempotentAutomationRunService.DistributedS3RunRequest request,
            Principal principal) {
        ApiIdempotencyCoordinator.StartResult result = automationRuns.startDistributedS3(
                idempotencyKey, request, clientId(principal));
        return accepted(result.runId(), "S3", "DISTRIBUTED", result.replayed());
    }

    @PostMapping(path = "/runs/distributed/clickhouse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunAccepted> startDistributedClickHouse(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody IdempotentAutomationRunService.DistributedClickHouseRunRequest request,
            Principal principal) {
        ApiIdempotencyCoordinator.StartResult result = automationRuns.startDistributedClickHouse(
                idempotencyKey, request, clientId(principal));
        return accepted(result.runId(), "CLICKHOUSE", "DISTRIBUTED", result.replayed());
    }

    @PostMapping(path = "/runs/distributed/kafka", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunAccepted> startDistributedKafka(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody IdempotentAutomationRunService.DistributedKafkaRunRequest request,
            Principal principal) {
        ApiIdempotencyCoordinator.StartResult result = automationRuns.startDistributedKafka(
                idempotencyKey, request, clientId(principal));
        return accepted(result.runId(), "KAFKA", "DISTRIBUTED", result.replayed());
    }

    private static ResponseEntity<RunAccepted> accepted(
            UUID runId, String testType, String scope, boolean replayed) {
        String monitorUrl = "/api/v1/runs/" + runId;
        RunAccepted body = new RunAccepted(runId, testType, scope, monitorUrl, replayed);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, monitorUrl)
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(replayed))
                .body(body);
    }

    private static String clientId(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    public record ProfileSummary(UUID id, String name, boolean defaultProfile) { }

    public record RunAccepted(UUID runId, String testType, String scope,
                              String monitorUrl, boolean replayed) { }
}
