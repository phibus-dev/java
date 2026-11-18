package dev.phibus.s3;

import static org.assertj.core.api.Assertions.assertThat;

import dev.phibus.s3.api.IdempotentAutomationRunService;
import dev.phibus.s3.distributed.AgentRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = {
        "s3perf.application-mode=AGENT",
        "s3perf.security.enabled=false",
        "s3perf.agent.coordinator-url=http://127.0.0.1:1",
        "s3perf.agent.identity-file=target/test-agent-identity.json",
        "s3perf.agent.heartbeat-interval-ms=600000",
        "s3perf.agent.poll-interval-ms=600000",
        "s3perf.agent.statistics-interval-ms=600000"
})
class AgentApplicationStartupTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContextStartsWithOnlyAgentRuntimeServices() {
        assertThat(context.getBeansOfType(AgentRuntimeService.class)).hasSize(1);
        assertThat(context.getBeansOfType(IdempotentAutomationRunService.class)).isEmpty();
    }
}
