package dev.phibus.s3.schedule;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.settings.ApplicationStateService;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRunService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TestScheduleServiceTest {
    @Test
    void rejectsManualCredentialsBeforeWritingSchedule() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TestScheduleService service = new TestScheduleService(mock(TestRunService.class), jdbc,
                new ObjectMapper(), mock(ApplicationStateService.class));
        TestRequest request = request("access", "secret");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TestScheduleService.CreateScheduleRequest(
                        "nightly", true, "0 0 * * * *", "UTC", request)));

        assertTrue(error.getMessage().contains("must use an S3 profile"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void responseCopyNeverContainsCredentials() {
        TestRequest safe = TestScheduleService.redact(request("access", "secret"));

        assertNull(safe.accessKey());
        assertNull(safe.secretKey());
    }

    private static TestRequest request(String accessKey, String secretKey) {
        return new TestRequest("http://localhost:9000", "bucket", "us-east-1", accessKey, secretKey,
                true, "object.bin", 32, 8, 4, 1, false, "UPLOAD");
    }
}
