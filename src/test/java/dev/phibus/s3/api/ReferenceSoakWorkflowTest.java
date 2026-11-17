package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReferenceSoakWorkflowTest {
    @Test
    void workflowIsReusableScheduledOptInAndAlwaysPreservesEvidence() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/reference-soak.yml"));

        assertThat(workflow).contains(
                "workflow_dispatch:",
                "workflow_call:",
                "schedule:",
                "vars.SOAK_ENABLED == 'true'",
                "secrets.EVO_SNT_TOKEN",
                "runs soak",
                "if: always()",
                "soak-summary.md",
                "actions/upload-artifact@v7",
                "retention-days: 90");
        assertThat(workflow).doesNotContain("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY");
    }

    @Test
    void exampleUsesStoredProfileInsteadOfCredentials() throws IOException {
        String example = Files.readString(Path.of("config/soak-reference.example.json"));

        assertThat(example).contains(
                "\"version\": 1",
                "\"scenarioId\"",
                "\"profileId\"",
                "\"iterations\"",
                "\"driftThresholds\"");
        assertThat(example.toLowerCase()).doesNotContain(
                "accesskey", "secretkey", "password", "credential", "token");
    }
}
