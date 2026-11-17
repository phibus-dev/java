package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReleaseCandidateWorkflowTest {
    @Test
    void releasePackagesTheOfflineSoakRunner() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/release.yml"));

        assertThat(workflow).contains(
                "default: 'v2.5.0-rc1'",
                "BUNDLE_NAME=\"evo-snt-offline-soak-${VERSION}\"",
                "\"${BUNDLE_NAME}.tar.gz\"",
                "scripts/reference-soak-local.sh",
                "deploy/systemd/evo-snt-reference-soak.service",
                "deploy/systemd/evo-snt-reference-soak.timer",
                "config/soak-reference.example.json",
                "config/reference-soak.env.example",
                "M4_SOAK_TESTS.md",
                "*.tar.gz");
    }

    @Test
    void postReleaseSmokeDefaultsMatchCandidateSchema() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/post-release-smoke.yml"));
        String script = Files.readString(Path.of("scripts/post-release-smoke.sh"));

        assertThat(workflow).contains(
                "default: '2.5.0-rc1'",
                "default: '27'",
                "github.event_name == 'pull_request' && '2.4.0'",
                "github.event_name == 'pull_request' && '24'",
                "github.event_name == 'workflow_dispatch' && inputs.durable_agent_registry");
        assertThat(script).contains(
                "VERSION=\"${VERSION:-2.5.0-rc1}\"",
                "EXPECTED_LATEST_MIGRATION=\"${EXPECTED_LATEST_MIGRATION:-27}\"",
                "EXPECT_DURABLE_AGENT_REGISTRY=\"${EXPECT_DURABLE_AGENT_REGISTRY:-true}\"");
    }
}
