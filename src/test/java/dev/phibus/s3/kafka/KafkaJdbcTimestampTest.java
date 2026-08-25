package dev.phibus.s3.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class KafkaJdbcTimestampTest {
    @Test
    void m3AndM4ConvertInstantBeforeJdbcUpdate() throws IOException {
        String m3 = Files.readString(Path.of("src/main/java/dev/phibus/s3/kafka/KafkaM3Service.java"));
        String m4 = Files.readString(Path.of("src/main/java/dev/phibus/s3/kafka/KafkaM4FailoverService.java"));

        assertThat(m3)
                .contains("toOffsetDateTime(run.startedAt())")
                .contains("toOffsetDateTime(run.finishedAt())")
                .doesNotContain("run.status(), run.finishedAt(),");
        assertThat(m4)
                .contains("toOffsetDateTime(started)")
                .contains("toOffsetDateTime(r.finishedAt())")
                .contains("toOffsetDateTime(r.failureDetectedAt())")
                .contains("toOffsetDateTime(r.recoveredAt())");
    }
}
