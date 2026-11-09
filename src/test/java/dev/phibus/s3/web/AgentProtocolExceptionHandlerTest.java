package dev.phibus.s3.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.phibus.s3.distributed.AgentRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentProtocolExceptionHandlerTest {
    @Test
    void invalidAgentTokenReturnsUnauthorizedInsteadOfServerError() throws Exception {
        AgentRegistry registry = mock(AgentRegistry.class);
        UUID agentId = UUID.randomUUID();
        doThrow(new SecurityException("Invalid agent token"))
                .when(registry).heartbeat(eq(agentId), eq("bad-token"), any());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(registry))
                .setControllerAdvice(new AgentProtocolExceptionHandler())
                .build();

        mvc.perform(post("/api/agents/{agentId}/heartbeat", agentId)
                        .header("X-Agent-Token", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"2.4.0\",\"cpuCount\":4,\"memoryBytes\":1024,\"tags\":{}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid agent token"));
    }
}
