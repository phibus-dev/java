package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class DistributedTestsPageResourceTest {

    @Test
    void distributedTestsPageUsesExternalScriptAndHasExplicitLoadingStates() throws IOException {
        String html = read("templates/distributed-tests.html");

        assertThat(html)
                .contains("<script src=\"/csrf-fetch.js\"></script>")
                .contains("<script src=\"/distributed-tests.js\"></script>")
                .contains("Получение списка агентов…")
                .contains("Получение истории распределённых запусков…")
                .doesNotContain("<script>")
                .doesNotContain("onclick=");
    }

    @Test
    void distributedTestsScriptReplacesLoadingStateOnEmptyErrorAndTimeout() throws IOException {
        String script = read("static/distributed-tests.js");

        assertThat(script)
                .contains("Нет доступных агентов")
                .contains("Распределённые запуски отсутствуют")
                .contains("Не удалось получить список агентов")
                .contains("Не удалось получить историю распределённых запусков")
                .contains("REQUEST_TIMEOUT_MS")
                .contains("AbortController")
                .contains("/api/agents")
                .contains("/api/distributed-tests");
    }

    @Test
    void distributedTestsScriptKeepsCreateAndRefreshActions() throws IOException {
        String script = read("static/distributed-tests.js");

        assertThat(script)
                .contains("startButton.addEventListener('click', startDistributedTest)")
                .contains("refreshButton.addEventListener('click'")
                .contains("method: 'POST'")
                .contains("JSON.parse(document.getElementById('request').value)");
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
