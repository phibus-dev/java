package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import dev.phibus.s3.lifecycle.UnifiedRunRerunService;
import dev.phibus.s3.lifecycle.UnifiedRunService;
import dev.phibus.s3.runtime.RuntimeResourceManager;
import dev.phibus.s3.test.TestType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VersionedRunApiControllerTest {
    private final UnifiedRunService runs = mock(UnifiedRunService.class);
    private final UnifiedRunHistoryStore history = mock(UnifiedRunHistoryStore.class);
    private final UnifiedRunRerunService rerun = mock(UnifiedRunRerunService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new VersionedRunApiController(runs, history, rerun))
            .setControllerAdvice(new VersionedRunApiExceptionHandler())
            .build();

    @Test
    void exposesVersionedRuntimeAndHistoryFilters() throws Exception {
        when(runs.list(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(history.search(any())).thenReturn(new UnifiedRunHistoryStore.Page(List.of(), 2, 25, 0, 0));

        mvc.perform(get("/api/v1/runs")
                        .param("scope", "distributed")
                        .param("testType", "kafka")
                        .param("status", "running")
                        .param("q", "nightly")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(runs).list(RunScope.DISTRIBUTED, TestType.KAFKA, RunStatus.RUNNING, "nightly", 50);

        mvc.perform(get("/api/v1/run-history")
                        .param("scope", "local")
                        .param("testType", "s3")
                        .param("from", "2026-11-11T08:00:00Z")
                        .param("to", "2026-11-11T09:00:00Z")
                        .param("page", "2")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(25));

        ArgumentCaptor<UnifiedRunHistoryStore.Filter> filter =
                ArgumentCaptor.forClass(UnifiedRunHistoryStore.Filter.class);
        verify(history).search(filter.capture());
        assertThat(filter.getValue().scope()).isEqualTo(RunScope.LOCAL);
        assertThat(filter.getValue().testType()).isEqualTo(TestType.S3);
        assertThat(filter.getValue().from()).isEqualTo(Instant.parse("2026-11-11T08:00:00Z"));
        assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-11-11T09:00:00Z"));
    }

    @Test
    void invalidInputUsesStableProblemJsonAndPreservesSafeRequestId() throws Exception {
        mvc.perform(get("/api/v1/runs")
                        .header(ApiErrors.REQUEST_ID_HEADER, "pipeline-42")
                        .param("scope", "remote"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(ApiErrors.REQUEST_ID_HEADER, "pipeline-42"))
                .andExpect(jsonPath("$.type").value("urn:evo-snt:error:invalid-request"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/v1/runs"))
                .andExpect(jsonPath("$.requestId").value("pipeline-42"))
                .andExpect(jsonPath("$.timestamp").exists());

        mvc.perform(get("/api/v1/run-history").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void missingRuntimeAndHistoryEntriesHaveDistinctStableCodes() throws Exception {
        UUID runtimeId = UUID.randomUUID();
        UUID historyId = UUID.randomUUID();
        when(runs.get(runtimeId)).thenThrow(new UnifiedRunService.RunNotFoundException(runtimeId));
        when(history.get(historyId)).thenReturn(null);

        mvc.perform(get("/api/v1/runs/{id}", runtimeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        mvc.perform(get("/api/v1/run-history/{id}", historyId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_HISTORY_NOT_FOUND"));
    }

    @Test
    void rerunConflictsAndCapacityErrorsAreMachineReadable() throws Exception {
        UUID conflictId = UUID.randomUUID();
        UUID capacityId = UUID.randomUUID();
        when(rerun.rerun(conflictId, null)).thenThrow(new IllegalStateException("Run is active"));
        when(rerun.rerun(capacityId, null)).thenThrow(
                new RuntimeResourceManager.RunCapacityExceededException("capacity exhausted"));

        mvc.perform(post("/api/v1/run-history/{id}/rerun", conflictId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_STATE_CONFLICT"));
        mvc.perform(post("/api/v1/run-history/{id}/rerun", capacityId))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));
    }

    @Test
    void internalErrorsDoNotExposeTheirDetails() throws Exception {
        when(runs.list(any(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("jdbc password=secret"));

        mvc.perform(get("/api/v1/runs"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("password=secret"))));
    }
}
