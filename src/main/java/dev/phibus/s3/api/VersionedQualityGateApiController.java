package dev.phibus.s3.api;

import dev.phibus.s3.quality.BenchmarkScenarioCatalog;
import dev.phibus.s3.quality.BenchmarkScenarioCatalog.Scenario;
import dev.phibus.s3.quality.QualityGatePolicyCatalog;
import dev.phibus.s3.quality.QualityGatePolicyCatalog.Policy;
import dev.phibus.s3.quality.RunQualityGateService;
import dev.phibus.s3.quality.RunQualityGateService.QualityGateRequest;
import dev.phibus.s3.quality.RunQualityGateService.QualityGateResult;
import dev.phibus.s3.test.TestType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class VersionedQualityGateApiController {
    private final QualityGatePolicyCatalog policies;
    private final BenchmarkScenarioCatalog scenarios;
    private final RunQualityGateService gates;

    public VersionedQualityGateApiController(QualityGatePolicyCatalog policies,
                                             BenchmarkScenarioCatalog scenarios,
                                             RunQualityGateService gates) {
        this.policies = policies;
        this.scenarios = scenarios;
        this.gates = gates;
    }

    @GetMapping("/quality-gates/policies")
    public List<Policy> policies() {
        return policies.list();
    }

    @GetMapping("/benchmark-scenarios")
    public List<Scenario> scenarios(@RequestParam(required = false) String testType) {
        return scenarios.list(parseType(testType));
    }

    @GetMapping("/benchmark-scenarios/{id}")
    public Scenario scenario(@PathVariable String id) {
        return scenarios.get(id);
    }

    @PostMapping("/run-history/{id}/quality-gate")
    public QualityGateResult evaluate(@PathVariable UUID id,
                                      @RequestBody(required = false) QualityGateRequest request) {
        return gates.evaluate(id, request);
    }

    private static TestType parseType(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return TestType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unsupported testType: " + value, error);
        }
    }
}
