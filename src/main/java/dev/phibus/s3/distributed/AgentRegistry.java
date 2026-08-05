package dev.phibus.s3.distributed;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentRegistry {
    private final Map<UUID, AgentRecord> agents = new ConcurrentHashMap<>();
    private final String registrationToken;

    public AgentRegistry(@Value("${s3perf.distributed.registration-token:change-me}") String registrationToken) {
        this.registrationToken = registrationToken;
    }

    public RegistrationResult register(RegistrationRequest request, String suppliedToken) {
        if (!registrationToken.equals(suppliedToken)) throw new SecurityException("Invalid agent registration token");
        UUID id = UUID.randomUUID();
        String agentToken = UUID.randomUUID() + "." + UUID.randomUUID();
        Instant now = Instant.now();
        AgentRecord record = new AgentRecord(id, request.name(), request.hostname(), request.address(), request.version(),
                request.cpuCount(), request.memoryBytes(), request.tags() == null ? Map.of() : Map.copyOf(request.tags()),
                now, now, AgentStatus.ONLINE, agentToken);
        agents.put(id, record);
        return new RegistrationResult(id, agentToken, now);
    }

    public AgentRecord heartbeat(UUID id, String token, HeartbeatRequest request) {
        AgentRecord current = authenticate(id, token);
        AgentRecord updated = new AgentRecord(current.id(), current.name(), current.hostname(), current.address(),
                request.version() == null ? current.version() : request.version(), request.cpuCount() <= 0 ? current.cpuCount() : request.cpuCount(),
                request.memoryBytes() <= 0 ? current.memoryBytes() : request.memoryBytes(), current.tags(), current.registeredAt(),
                Instant.now(), current.status() == AgentStatus.BUSY ? AgentStatus.BUSY : AgentStatus.ONLINE, current.agentToken());
        agents.put(id, updated);
        return updated;
    }

    public List<AgentView> list() {
        Instant now = Instant.now();
        return agents.values().stream().map(agent -> {
            boolean online = Duration.between(agent.lastSeenAt(), now).compareTo(Duration.ofSeconds(45)) <= 0;
            AgentStatus status = online ? agent.status() : AgentStatus.OFFLINE;
            return new AgentView(agent.id(), agent.name(), agent.hostname(), agent.address(), agent.version(), agent.cpuCount(),
                    agent.memoryBytes(), agent.tags(), agent.registeredAt(), agent.lastSeenAt(), status);
        }).sorted(Comparator.comparing(AgentView::name)).toList();
    }

    public AgentRecord authenticate(UUID id, String token) {
        AgentRecord agent = agents.get(id);
        if (agent == null) throw new IllegalArgumentException("Agent not found");
        if (token == null || !agent.agentToken().equals(token)) throw new SecurityException("Invalid agent token");
        return agent;
    }

    public void requireOnline(UUID id) {
        AgentRecord agent = agents.get(id);
        if (agent == null) throw new IllegalArgumentException("Agent not found: " + id);
        if (Duration.between(agent.lastSeenAt(), Instant.now()).compareTo(Duration.ofSeconds(45)) > 0)
            throw new IllegalStateException("Agent is offline: " + id);
    }

    public void markBusy(UUID id, boolean busy) {
        AgentRecord current = agents.get(id);
        if (current == null) throw new IllegalArgumentException("Agent not found");
        agents.put(id, new AgentRecord(current.id(), current.name(), current.hostname(), current.address(), current.version(),
                current.cpuCount(), current.memoryBytes(), current.tags(), current.registeredAt(), current.lastSeenAt(),
                busy ? AgentStatus.BUSY : AgentStatus.ONLINE, current.agentToken()));
    }

    public enum AgentStatus { ONLINE, OFFLINE, BUSY }
    public record RegistrationRequest(String name, String hostname, String address, String version,
                                      int cpuCount, long memoryBytes, Map<String, String> tags) { }
    public record HeartbeatRequest(String version, int cpuCount, long memoryBytes) { }
    public record RegistrationResult(UUID agentId, String agentToken, Instant registeredAt) { }
    public record AgentRecord(UUID id, String name, String hostname, String address, String version,
                              int cpuCount, long memoryBytes, Map<String, String> tags, Instant registeredAt,
                              Instant lastSeenAt, AgentStatus status, String agentToken) { }
    public record AgentView(UUID id, String name, String hostname, String address, String version,
                            int cpuCount, long memoryBytes, Map<String, String> tags, Instant registeredAt,
                            Instant lastSeenAt, AgentStatus status) { }
}
