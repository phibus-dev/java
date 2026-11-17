package dev.phibus.s3.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OfflineSoakRunnerTest {
    @Test
    void serviceUsesSystemdCredentialsAndHardenedLocalStorage() throws IOException {
        String service = Files.readString(
                Path.of("deploy/systemd/evo-snt-reference-soak.service"));

        assertThat(service).contains(
                "User=evo-snt",
                "LoadCredential=api-token:/etc/evo-snt/reference-soak.token",
                "StateDirectory=evo-snt-reference-soak",
                "StateDirectoryMode=0700",
                "UMask=0077",
                "ProtectSystem=strict",
                "TimeoutStartSec=infinity");
        assertThat(service.toLowerCase()).doesNotContain("github", "evo_snt_token=");
    }

    @Test
    void timerRunsWeeklyWithoutDependingOnGitHub() throws IOException {
        String timer = Files.readString(
                Path.of("deploy/systemd/evo-snt-reference-soak.timer"));

        assertThat(timer).contains(
                "OnCalendar=Sun *-*-* 01:23:00 UTC",
                "Persistent=true",
                "RandomizedDelaySec=15m");
        assertThat(timer.toLowerCase()).doesNotContain("github");
    }

    @Test
    void runnerSeparatesPrivateEvidenceFromAnonymizedSummary() throws IOException {
        String runner = Files.readString(Path.of("scripts/reference-soak-local.sh"));

        assertThat(runner).contains(
                "--token-file \"$token_file\"",
                "--output-dir \"$private_dir\"",
                "--public-summary-dir \"$public_dir\"",
                "--idempotency-prefix \"$run_prefix\"");
        assertThat(runner.toLowerCase()).doesNotContain("github", "curl", "wget");
    }
}
