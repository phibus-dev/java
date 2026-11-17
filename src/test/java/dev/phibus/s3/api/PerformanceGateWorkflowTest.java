package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PerformanceGateWorkflowTest {
    @Test
    void reusableWorkflowPreservesEvidenceAndGateExitCode() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/performance-gate.yml"));

        assertThat(workflow).contains("workflow_call:", "workflow_dispatch:", "EVO_SNT_TOKEN:",
                "runs assert", "--baseline", "PIPESTATUS[0]", "if: always()",
                "quality-gate.json", "actions/upload-artifact@v4");
    }
}
