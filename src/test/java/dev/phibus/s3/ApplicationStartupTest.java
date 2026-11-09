package dev.phibus.s3;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "s3perf.security.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class ApplicationStartupTest {

    @Test
    void applicationContextStartsInBootstrapMode() {
        // Context creation is the assertion: every runtime component must be constructible.
    }
}
