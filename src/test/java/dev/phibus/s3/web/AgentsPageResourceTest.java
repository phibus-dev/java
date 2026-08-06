package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AgentsPageResourceTest {

    @Test
    void agentsPageUsesExternalScriptAndHasNoInlineHandlers() throws IOException {
        String html = read("templates/agents.html");

        assertThat(html)
                .contains("<script src=\"/agents.js\"></script>")
                .contains("Получение списка агентов…")
                .doesNotContain("<script>")
                .doesNotContain("onclick=");
    }

    @Test
    void agentsScriptHandlesEmptyAndFailedResponses() throws IOException {
        String script = read("static/agents.js");

        assertThat(script)
                .contains("Агенты не зарегистрированы")
                .contains("Не удалось получить список агентов")
                .contains("REQUEST_TIMEOUT_MS")
                .contains("data-action");
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
