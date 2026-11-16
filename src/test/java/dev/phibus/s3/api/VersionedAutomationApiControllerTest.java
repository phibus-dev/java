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

import dev.phibus.s3.settings.S3ProfileService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VersionedAutomationApiControllerTest {
    private final S3ProfileService profiles = mock(S3ProfileService.class);
    private final IdempotentS3RunService runs = mock(IdempotentS3RunService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new VersionedAutomationApiController(profiles, runs))
            .setControllerAdvice(new VersionedRunApiExceptionHandler())
            .build();

    @Test
    void listsOnlySafeProfileSelectionFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(profiles.list()).thenReturn(List.of(new S3ProfileService.Profile(
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
    void startsAndReplaysWithMachineReadableHeaders() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(runs.start(eq("pipeline-42"), any(S3RunRequest.class), eq("ci-user")))
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
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void rejectsMissingHeaderAndConflictingKeyAsStableProblems() throws Exception {
        UUID profileId = UUID.randomUUID();
        String body = "{\"profileId\":\"" + profileId + "\"}";

        mvc.perform(post("/api/v1/runs/s3")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        when(runs.start(eq("pipeline-42"), any(S3RunRequest.class), eq(null)))
                .thenThrow(new ApiIdempotencyStore.IdempotencyConflictException());
        mvc.perform(post("/api/v1/runs/s3")
                        .header(VersionedAutomationApiController.IDEMPOTENCY_KEY_HEADER, "pipeline-42")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        UUID missingProfile = UUID.randomUUID();
        String missingBody = "{\"profileId\":\"" + missingProfile + "\"}";
        when(runs.start(eq("pipeline-43"), any(S3RunRequest.class), eq(null)))
                .thenThrow(new IdempotentS3RunService.ProfileNotFoundException(missingProfile));
        mvc.perform(post("/api/v1/runs/s3")
                        .header(VersionedAutomationApiController.IDEMPOTENCY_KEY_HEADER, "pipeline-43")
                        .contentType(MediaType.APPLICATION_JSON).content(missingBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }
}
