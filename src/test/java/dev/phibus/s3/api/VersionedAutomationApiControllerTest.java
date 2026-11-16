package dev.phibus.s3.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.kafka.KafkaProfileService;
import dev.phibus.s3.settings.S3ProfileService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VersionedAutomationApiControllerTest {
    private final S3ProfileService s3Profiles = mock(S3ProfileService.class);
    private final ClickHouseProfileService clickHouseProfiles = mock(ClickHouseProfileService.class);
    private final KafkaProfileService kafkaProfiles = mock(KafkaProfileService.class);
    private final IdempotentS3RunService s3Runs = mock(IdempotentS3RunService.class);
    private final IdempotentAutomationRunService automationRuns = mock(IdempotentAutomationRunService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new VersionedAutomationApiController(s3Profiles, clickHouseProfiles, kafkaProfiles,
                            s3Runs, automationRuns))
            .setControllerAdvice(new VersionedRunApiExceptionHandler())
            .build();

    @Test
    void listsOnlySafeProfileSelectionFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(s3Profiles.list()).thenReturn(List.of(new S3ProfileService.Profile(
                id, "primary", "https://s3.internal", "us-east-1", "secret-bucket",
                true, "VAULT", "secret/path", "access", "secret", "token",
                "/etc/ca.pem", true, null, null)));

        mvc.perform(get("/api/v1/profiles/s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].name").value("primary"))
                .andExpect(jsonPath("$[0].defaultProfile").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret-bucket"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret/path"))));
    }

    @Test
    void startsAndReplaysS3WithMachineReadableHeaders() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(s3Runs.start(eq("pipeline-42"), any(S3RunRequest.class), eq("ci-user")))
                .thenReturn(new IdempotentS3RunService.StartResult(runId, true));

        mvc.perform(post("/api/v1/runs/s3")
                        .principal(() -> "ci-user")
                        .header(VersionedAutomationApiController.IDEMPOTENCY_KEY_HEADER, "pipeline-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileId\":\"" + profileId + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/runs/" + runId))
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.testType").value("S3"))
                .andExpect(jsonPath("$.scope").value("LOCAL"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void startsLocalClickHouseAndDistributedKafkaThroughTheSameContract() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID clickHouseRun = UUID.randomUUID();
        UUID kafkaRun = UUID.randomUUID();
        when(automationRuns.startClickHouse(eq("clickhouse-1"), any(ClickHouseTestRequest.class), eq(null)))
                .thenReturn(new ApiIdempotencyCoordinator.StartResult(clickHouseRun, false));
        when(automationRuns.startDistributedKafka(eq("kafka-1"),
                any(IdempotentAutomationRunService.DistributedKafkaRunRequest.class), eq(null)))
                .thenReturn(new ApiIdempotencyCoordinator.StartResult(kafkaRun, false));

        mvc.perform(post("/api/v1/runs/clickhouse")
                        .header(VersionedAutomationApiController.IDEMPOTENCY_KEY_HEADER, "clickhouse-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileId":"%s","table":"events","operation":"INSERT",
                                 "concurrency":2,"batchSize":1000,"rowCount":10000,
                                 "durationSeconds":0,"warmupSeconds":0,"payloadBytes":128,
                                 "queryCount":1,"autoCreateTable":true}
                                """.formatted(profileId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(clickHouseRun.toString()))
                .andExpect(jsonPath("$.testType").value("CLICKHOUSE"))
                .andExpect(jsonPath("$.scope").value("LOCAL"));

        mvc.perform(post("/api/v1/runs/distributed/kafka")
                        .header(VersionedAutomationApiController.IDEMPOTENCY_KEY_HEADER, "kafka-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"distributed-kafka","agentIds":["%s"],"testRequest":{
                                 "profileId":"%s","topic":"events","messageSizeBytes":1024,
                                 "messageCount":1000,"producerThreads":2,"acks":"all",
                                 "compressionType":"none","batchSize":16384,"lingerMs":5,
                                 "enableIdempotence":true,"targetMessagesPerSec":0,
                                 "maxInFlightMessages":10000}}
                                """.formatted(agentId, profileId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(kafkaRun.toString()))
                .andExpect(jsonPath("$.testType").value("KAFKA"))
                .andExpect(jsonPath("$.scope").value("DISTRIBUTED"));
    }

    @Test
    void rejectsMissingHeaderAndConflictingKeyAsStableProblems() throws Exception {
        UUID profileId = UUID.randomUUID();
        String body = "{\"profileId\":\"" + profileId + "\"}";

        mvc.perform(post("/api/v1/runs/s3")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        when(s3Runs.start(eq("pipeline-42"), any(S3RunRequest.class), eq(null)))
                .thenThrow(new ApiIdempotencyStore.IdempotencyConflictException());
        mvc.perform(post("/api/v1/runs/s3")
                        .header(VersionedAutomationApiController.IDEMPOTENCY_KEY_HEADER, "pipeline-42")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        UUID missingProfile = UUID.randomUUID();
        String missingBody = "{\"profileId\":\"" + missingProfile + "\"}";
        when(s3Runs.start(eq("pipeline-43"), any(S3RunRequest.class), eq(null)))
                .thenThrow(new IdempotentS3RunService.ProfileNotFoundException(missingProfile));
        mvc.perform(post("/api/v1/runs/s3")
                        .header(VersionedAutomationApiController.IDEMPOTENCY_KEY_HEADER, "pipeline-43")
                        .contentType(MediaType.APPLICATION_JSON).content(missingBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }
}
