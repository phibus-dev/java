package dev.phibus.s3.distributed;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
    private final Map<UUID, AgentManagement> management = new ConcurrentHashMap<>();
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
        management.put(id, new AgentManagement(true, null, false, null, now));
        return new RegistrationResult(id, agentToken, now);
    }

    public AgentRecord heartbeat(UUID id, String token, HeartbeatRequest request) {
        AgentRecord current = authenticate(id, token);
        Map<String, String> tags = request.tags() == null || request.tags().isEmpty() ? current.tags() : Map.copyOf(request.tags());
        AgentRecord updated = new AgentRecord(current.id(), current.name(), current.hostname(), current.address(),
                request.version() == null ? current.version() : request.version(),
                request.cpuCount() <= 0 ? current.cpuCount() : request.cpuCount(),
                request.memoryBytes() <= 0 ? current.memoryBytes() : request.memoryBytes(), tags, current.registeredAt(),
                Instant.now(), current.status() == AgentStatus.BUSY ? AgentStatus.BUSY : AgentStatus.ONLINE, current.agentToken());
        agents.put(id, updated);
        AgentManagement state = management.getOrDefault(id, AgentManagement.defaults());
        if (state.updateRequested() && state.desiredVersion() != null && state.desiredVersion().equals(updated.version()))
            management.put(id, new AgentManagement(state.enabled(), state.desiredVersion(), false, Instant.now(), state.changedAt()));
        return updated;
    }

    public List<AgentView> list() {
        Instant now = Instant.now();
        return agents.values().stream().map(agent -> {
            AgentManagement state = management.getOrDefault(agent.id(), AgentManagement.defaults());
            boolean online = Duration.between(agent.lastSeenAt(), now).compareTo(Duration.ofSeconds(45)) <= 0;
            AgentStatus status = !state.enabled() ? AgentStatus.DISABLED : online ? agent.status() : AgentStatus.OFFLINE;
            return new AgentView(agent.id(), agent.name(), agent.hostname(), agent.address(), agent.version(), agent.cpuCount(),
                    agent.memoryBytes(), agent.tags(), agent.registeredAt(), agent.lastSeenAt(), status, state.enabled(),
                    state.desiredVersion(), state.updateRequested(), state.updateCompletedAt(), state.changedAt());
        }).sorted(Comparator.comparing(AgentView::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    public AgentRecord authenticate(UUID id, String token) {
        AgentRecord agent = agents.get(id);
        if (agent == null) throw new IllegalArgumentException("Agent not found");
        AgentManagement state = management.getOrDefault(id, AgentManagement.defaults());
        if (!state.enabled()) throw new SecurityException("Agent is disabled");
        if (token == null || !agent.agentToken().equals(token)) throw new SecurityException("Invalid agent token");
        return agent;
    }

    public AgentView setEnabled(UUID id, boolean enabled) {
        requirePresent(id);
        AgentManagement current = management.getOrDefault(id, AgentManagement.defaults());
        management.put(id, new AgentManagement(enabled, current.desiredVersion(), current.updateRequested(),
                current.updateCompletedAt(), Instant.now()));
        return view(id);
    }

    public AgentView requestUpdate(UUID id, String desiredVersion) {
        requirePresent(id);
        if (desiredVersion == null || desiredVersion.isBlank()) throw new IllegalArgumentException("Desired version is required");
        AgentManagement current = management.getOrDefault(id, AgentManagement.defaults());
        management.put(id, new AgentManagement(current.enabled(), desiredVersion.trim(), true, null, Instant.now()));
        return view(id);
    }

    public void revokeIdentity(UUID id) {
        requirePresent(id);
        agents.remove(id);
        management.remove(id);
    }

    public void requireOnline(UUID id) {
        AgentRecord agent = agents.get(id);
        if (agent == null) throw new IllegalArgumentException("Agent not found: " + id);
        AgentManagement state = management.getOrDefault(id, AgentManagement.defaults());
        if (!state.enabled()) throw new IllegalStateException("Agent is disabled: " + id);
        if (Duration.between(agent.lastSeenAt(), Instant.now()).compareTo(Duration.ofSeconds(45)) > 0)
            throw new IllegalStateException("Agent is offline: " + id);
    }

    public void requireCapability(UUID id, String capability) {
        requireOnline(id);
        AgentRecord agent = agents.get(id);
        String capabilities = agent.tags().getOrDefault("capabilities", "S3");
        boolean supported = Arrays.stream(capabilities.split(","))
                .map(String::trim).anyMatch(value -> value.equalsIgnoreCase(capability));
        if (!supported) throw new IllegalStateException("Agent " + id + " does not support " + capability);
    }

    public void markBusy(UUID id, boolean busy) {
        AgentRecord current = agents.get(id);
        if (current == null) throw new IllegalArgumentException("Agent not found");
        agents.put(id, new AgentRecord(current.id(), current.name(), current.hostname(), current.address(), current.version(),
                current.cpuCount(), current.memoryBytes(), current.tags(), current.registeredAt(), current.lastSeenAt(),
                busy ? AgentStatus.BUSY : AgentStatus.ONLINE, current.agentToken()));
    }

    private AgentView view(UUID id) { return list().stream().filter(agent -> agent.id().equals(id)).findFirst().orElseThrow(); }
    private void requirePresent(UUID id) { if (!agents.containsKey(id)) throw new IllegalArgumentException("Agent not found: " + id); }

    public enum AgentStatus { ONLINE, OFFLINE, BUSY, DISABLED }
    public record RegistrationRequest(String name, String hostname, String address, String version,
                                      int cpuCount, long memoryBytes, Map<String, String> tags) { }
    public record HeartbeatRequest(String version, int cpuCount, long memoryBytes, Map<String, String> tags) { }
    public record RegistrationResult(UUID agentId, String agentToken, Instant registeredAt) { }
    public record AgentRecord(UUID id, String name, String hostname, String address, String version,
                              int cpuCount, long memoryBytes, Map<String, String> tags, Instant registeredAt,
                              Instant lastSeenAt, AgentStatus status, String agentToken) { }
    public record AgentView(UUID id, String name, String hostname, String address, String version,
                            int cpuCount, long memoryBytes, Map<String, String> tags, Instant registeredAt,
                            Instant lastSeenAt, AgentStatus status, boolean enabled, String desiredVersion,
                            boolean updateRequested, Instant updateCompletedAt, Instant managementChangedAt) { }
    private record AgentManagement(boolean enabled, String desiredVersion, boolean updateRequested,
                                   Instant updateCompletedAt, Instant changedAt) {
        static AgentManagement defaults() { return new AgentManagement(true, null, false, null, Instant.EPOCH); }
    }
}
