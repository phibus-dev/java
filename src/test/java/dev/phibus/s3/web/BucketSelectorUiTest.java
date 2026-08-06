package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BucketSelectorUiTest {

    @Test
    void rendersSelectableBucketListAndDoesNotAutoSelectFirstBucket() throws IOException {
        String template = resource("/templates/index.html");
        String script = resource("/static/app.js");

        assertThat(template)
                .contains("id=\"bucket-select\"")
                .contains("id=\"load-buckets\"")
                .contains("Выберите бакет");

        assertThat(script)
                .contains("applyBucketOptions")
                .contains("resetBucketSelector")
                .contains("localeCompare")
                .contains("el('bucket-select').addEventListener('change'")
                .contains("el('bucket').value=event.target.value")
                .doesNotContain("el('bucket').value=buckets[0]")
                .doesNotContain("bucket.value=buckets[0]");
    }

    @Test
    void keepsSelectedBucketInRequestPayload() throws IOException {
        String script = resource("/static/app.js");

        assertThat(script)
                .contains("bucket:value('bucket').trim()")
                .contains("el('bucket').value=event.target.value");
    }

    @Test
    void clearsBucketOptionsWhenProfileChanges() throws IOException {
        String script = resource("/static/app.js");

        assertThat(script)
                .contains("resetBucketSelector(true)")
                .contains("setBucketLocked(locked&&!!p.bucket)");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
