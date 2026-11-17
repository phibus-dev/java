package dev.phibus.s3.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.quality.BenchmarkScenarioCatalog;
import dev.phibus.s3.quality.QualityGatePolicyCatalog;
import dev.phibus.s3.quality.RunQualityGateService;
import dev.phibus.s3.quality.RunQualityGateService.QualityGateRequest;
import dev.phibus.s3.quality.RunQualityGateService.QualityGateResult;
import dev.phibus.s3.test.TestType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VersionedQualityGateApiControllerTest {
    private final RunQualityGateService gates = mock(RunQualityGateService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new VersionedQualityGateApiController(
                    new QualityGatePolicyCatalog(), new BenchmarkScenarioCatalog(), gates))
            .setControllerAdvice(new VersionedRunApiExceptionHandler()).build();

    @Test
    void exposesPoliciesAndFilteredScenarios() throws Exception {
        mvc.perform(get("/api/v1/quality-gates/policies"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("SMOKE"));
        mvc.perform(get("/api/v1/benchmark-scenarios").param("testType", "kafka"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].testType").value("KAFKA"));
        mvc.perform(get("/api/v1/benchmark-scenarios/S3_UPLOAD_THROUGHPUT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parameters.objectSizeMiB").value(1024));
    }

    @Test
    void evaluatesGateAndDeserializesOverrides() throws Exception {
        UUID runId = UUID.randomUUID();
        when(gates.evaluate(eq(runId), any())).thenReturn(new QualityGateResult(runId, null,
                "SMOKE", "PASS", Instant.now(), TestType.S3, RunScope.LOCAL,
                RunStatus.COMPLETED, 0, null, List.of()));

        mvc.perform(post("/api/v1/run-history/{id}/quality-gate", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"SMOKE\",\"overrides\":{\"maxP95LatencyMs\":500}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verdict").value("PASS"))
                .andExpect(jsonPath("$.passed").value(true));
        verify(gates).evaluate(eq(runId), any(QualityGateRequest.class));
    }

    @Test
    void invalidCatalogFilterUsesProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/benchmark-scenarios").param("testType", "redis"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
