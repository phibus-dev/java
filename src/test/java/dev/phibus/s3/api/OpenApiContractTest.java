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

    @Test
    void versionedContractIsValidYamlAndCoversEveryCanonicalRunOperation() throws IOException {
        Map<String, Object> root = loadContract();
        Map<String, Object> info = map(root.get("info"));
        Map<String, Object> paths = map(root.get("paths"));

        assertThat(root.get("openapi")).isEqualTo("3.0.3");
        assertThat(info.get("version")).isEqualTo("1.0.0");
        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                "/runs", "/runs/{id}", "/run-history", "/run-history/{id}",
                "/run-history/{id}/rerun");

        Set<String> operationIds = new LinkedHashSet<>();
        paths.values().stream().map(OpenApiContractTest::map).forEach(path -> path.forEach((method, value) -> {
            if (!Set.of("get", "post", "put", "patch", "delete").contains(method)) return;
            Object operationId = map(value).get("operationId");
            assertThat(operationId).as("operationId for %s", method).isInstanceOf(String.class);
            assertThat(operationIds.add(operationId.toString()))
                    .as("operationId %s must be unique", operationId).isTrue();
        }));
        assertThat(operationIds).containsExactlyInAnyOrder(
                "listRuns", "getRun", "listRunHistory", "getRunHistoryEntry", "rerunFromHistory");
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
                "AUTHENTICATION_REQUIRED", "ACCESS_DENIED", "INTERNAL_ERROR");
        assertThat(map(securitySchemes.get("bearerAuth")))
                .containsEntry("type", "http")
                .containsEntry("scheme", "bearer");
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
