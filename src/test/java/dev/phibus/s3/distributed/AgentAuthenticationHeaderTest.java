package dev.phibus.s3.distributed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentAuthenticationHeaderTest {

    @Test
    void agentUsesDedicatedTokenHeaderThatIsNotConsumedByOidc() throws IOException {
        String runtime = Files.readString(Path.of(
                "src/main/java/dev/phibus/s3/distributed/AgentRuntimeService.java"));
        String agentController = Files.readString(Path.of(
                "src/main/java/dev/phibus/s3/web/AgentController.java"));
        String distributedController = Files.readString(Path.of(
                "src/main/java/dev/phibus/s3/web/DistributedTestController.java"));

        assertThat(runtime)
                .contains("AGENT_TOKEN_HEADER = \"X-Agent-Token\"")
                .doesNotContain("HttpHeaders.AUTHORIZATION,\"Bearer \"+current.agentToken()")
                .doesNotContain("HttpHeaders.AUTHORIZATION,\"Bearer \"+agent.agentToken()");
        assertThat(agentController).contains("X-Agent-Token");
        assertThat(distributedController).contains("X-Agent-Token");
    }
}
