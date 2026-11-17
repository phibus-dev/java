package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class OpenApiContractTest {
    private static final Path CONTRACT = Path.of("src/main/resources/static/api/v1/openapi.yaml");
    private static final Set<String> START_PATHS = Set.of(
            "/runs/s3", "/runs/clickhouse", "/runs/kafka",
            "/runs/distributed/s3", "/runs/distributed/clickhouse", "/runs/distributed/kafka");

    @Test
    void versionedContractIsValidYamlAndCoversEveryCanonicalRunOperation() throws IOException {
        Map<String, Object> root = loadContract();
        Map<String, Object> info = map(root.get("info"));
        Map<String, Object> paths = map(root.get("paths"));

        assertThat(root.get("openapi")).isEqualTo("3.0.3");
        assertThat(info.get("version")).isEqualTo("1.3.0");
        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                "/profiles/s3", "/profiles/clickhouse", "/profiles/kafka",
                "/runs/s3", "/runs/clickhouse", "/runs/kafka",
                "/runs/distributed/s3", "/runs/distributed/clickhouse", "/runs/distributed/kafka",
                "/runs", "/runs/{id}", "/run-history", "/run-history/{id}",
                "/run-history/{id}/export/{format}", "/run-history/{id}/rerun",
                "/quality-gates/policies", "/benchmark-scenarios", "/benchmark-scenarios/{id}",
                "/run-history/{id}/quality-gate");

        Set<String> operationIds = new LinkedHashSet<>();
        paths.values().stream().map(OpenApiContractTest::map).forEach(path -> path.forEach((method, value) -> {
            if (!Set.of("get", "post", "put", "patch", "delete").contains(method)) return;
            Object operationId = map(value).get("operationId");
            assertThat(operationId).as("operationId for %s", method).isInstanceOf(String.class);
            assertThat(operationIds.add(operationId.toString()))
                    .as("operationId %s must be unique", operationId).isTrue();
        }));
        assertThat(operationIds).containsExactlyInAnyOrder(
                "listS3Profiles", "listClickHouseProfiles", "listKafkaProfiles",
                "startS3Run", "startClickHouseRun", "startKafkaRun",
                "startDistributedS3Run", "startDistributedClickHouseRun", "startDistributedKafkaRun",
                "listRuns", "getRun", "listRunHistory", "getRunHistoryEntry",
                "exportRunHistoryEntry", "rerunFromHistory", "listQualityGatePolicies",
                "listBenchmarkScenarios", "getBenchmarkScenario", "evaluateRunQualityGate");
    }

    @Test
    void contractDefinesStableProblemDetailsAndBearerAuthentication() throws IOException {
        Map<String, Object> root = loadContract();
        Map<String, Object> components = map(root.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> errors = map(schemas.get("ApiError"));
        Map<String, Object> properties = map(errors.get("properties"));
        Map<String, Object> codes = map(properties.get("code"));
        Map<String, Object> securitySchemes = map(components.get("securitySchemes"));

        assertThat(errors.get("additionalProperties")).isEqualTo(false);
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "type", "title", "status", "code", "detail", "instance", "requestId", "timestamp");
        assertThat(list(codes.get("enum"))).contains("INVALID_REQUEST", "RUN_NOT_FOUND",
                "RUN_HISTORY_NOT_FOUND", "RUN_STATE_CONFLICT", "CAPACITY_EXCEEDED",
                "AUTHENTICATION_REQUIRED", "ACCESS_DENIED", "INTERNAL_ERROR",
                "IDEMPOTENCY_CONFLICT", "PROFILE_NOT_FOUND");
        assertThat(map(securitySchemes.get("bearerAuth")))
                .containsEntry("type", "http")
                .containsEntry("scheme", "bearer");
    }

    @Test
    void everyStartRequiresAClientKeyAndDocumentsReplay() throws IOException {
        Map<String, Object> root = loadContract();
        Map<String, Object> paths = map(root.get("paths"));
        Map<String, Object> parameters = map(map(root.get("components")).get("parameters"));
        Map<String, Object> idempotencyKey = map(parameters.get("IdempotencyKey"));

        assertThat(idempotencyKey)
                .containsEntry("name", "Idempotency-Key")
                .containsEntry("in", "header")
                .containsEntry("required", true);

        for (String path : START_PATHS) {
            Map<String, Object> operation = map(map(paths.get(path)).get("post"));
            Map<String, Object> requestBody = map(operation.get("requestBody"));
            Map<String, Object> responses = map(operation.get("responses"));
            assertThat(requestBody.get("required")).as(path).isEqualTo(true);
            assertThat(operation.get("parameters").toString()).as(path).contains("IdempotencyKey");
            assertThat(responses.keySet()).as(path).contains("202", "409", "429");
        }

        Map<String, Object> accepted = map(map(map(paths.get("/runs/s3")).get("post"))
                .get("responses"));
        Map<String, Object> acceptedResponse = map(accepted.get("202"));
        assertThat(map(acceptedResponse.get("headers")).keySet())
                .containsExactlyInAnyOrder("Location", "Idempotency-Replayed");
    }

    @Test
    void profileSelectionAndExportSchemasDoNotExposeCredentials() throws IOException {
        String contract = Files.readString(CONTRACT);
        Map<String, Object> schemas = map(map(loadContract().get("components")).get("schemas"));

        assertThat(map(schemas.get("ProfileSummary")).keySet())
                .contains("type", "additionalProperties", "required", "properties");
        assertThat(list(map(schemas.get("TestType")).get("enum")))
                .containsExactly("S3", "CLICKHOUSE", "KAFKA");
        assertThat(list(map(schemas.get("RunScope")).get("enum")))
                .containsExactly("LOCAL", "DISTRIBUTED");
        assertThat(contract).doesNotContain("secretKey", "accessKey", "password");
    }

    private static Map<String, Object> loadContract() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return map(new Yaml(new SafeConstructor(options)).load(Files.readString(CONTRACT)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> list(Object value) {
        assertThat(value).isInstanceOf(java.util.List.class);
        return (java.util.List<String>) value;
    }
}
