package dev.phibus.s3.distributed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRegistryTest {
    @Test
    void registersAndUpdatesAgent() {
        AgentRegistry registry = new AgentRegistry("registration-secret");
        AgentRegistry.RegistrationResult registration = registry.register(
                new AgentRegistry.RegistrationRequest("agent-1", "host-1", "https://agent-1:8080", "1.3.0", 8,
                        16L * 1024 * 1024 * 1024, Map.of("dc", "dc1")), "registration-secret");

        assertEquals(1, registry.list().size());
        registry.heartbeat(registration.agentId(), registration.agentToken(),
                new AgentRegistry.HeartbeatRequest("1.3.1", 12, 32L * 1024 * 1024 * 1024));
        assertEquals("1.3.1", registry.list().getFirst().version());
        assertEquals(12, registry.list().getFirst().cpuCount());
    }

    @Test
    void rejectsInvalidRegistrationToken() {
        AgentRegistry registry = new AgentRegistry("expected");
        assertThrows(SecurityException.class, () -> registry.register(
                new AgentRegistry.RegistrationRequest("agent", "host", "http://agent", "1", 1, 1, Map.of()), "wrong"));
    }
}
