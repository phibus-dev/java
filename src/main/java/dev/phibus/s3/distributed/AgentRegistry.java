package dev.phibus.s3.distributed;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentRegistry {
    private final Map<UUID, AgentRecord> agents = new ConcurrentHashMap<>();
    private final Map<UUID, AgentManagement> management = new ConcurrentHashMap<>();
    private final Set<UUID> recoveredBusyAgents = ConcurrentHashMap.newKeySet();
    private final AgentRegistryRepository repository;
    private final String registrationToken;
    private volatile boolean loaded;

    @Autowired
    AgentRegistry(AgentRegistryRepository repository,
                  @Value("${s3perf.distributed.registration-token:change-me}") String registrationToken) {
        this.repository = repository;
        this.registrationToken = registrationToken == null ? "" : registrationToken.trim();
    }

    /** In-memory registry used by focused unit tests. */
    public AgentRegistry(String registrationToken) {
        this.repository = null;
        this.registrationToken = registrationToken == null ? "" : registrationToken.trim();
        this.loaded = true;
    }

    public synchronized RegistrationResult register(RegistrationRequest request, String suppliedToken) {
        ensureLoaded();
        if (registrationToken.isBlank() || "change-me".equals(registrationToken)) {
            throw new IllegalStateException(
                    "Agent registration is disabled until s3perf.distributed.registration-token is configured");
        }
        if (!registrationToken.equals(suppliedToken)) throw new SecurityException("Invalid agent registration token");

        AgentRecord existing = agents.values().stream()
                .filter(agent -> sameIdentity(agent, request))
                .findFirst().orElse(null);
        String agentToken = newAgentToken();
        String tokenHash = hashToken(agentToken);
        Instant now = Instant.now();
        if (existing != null) {
            AgentRecord refreshed = new AgentRecord(existing.id(), request.name(), request.hostname(), request.address(),
                    request.version(), request.cpuCount(), request.memoryBytes(),
                    request.tags() == null ? Map.of() : Map.copyOf(request.tags()), existing.registeredAt(), now,
                    AgentStatus.ONLINE, tokenHash);
            AgentManagement state = management.getOrDefault(existing.id(), AgentManagement.defaults());
            persist(refreshed, state);
            agents.put(existing.id(), refreshed);
            management.putIfAbsent(existing.id(), state);
            recoveredBusyAgents.remove(existing.id());
            return new RegistrationResult(existing.id(), agentToken, existing.registeredAt());
        }

        UUID id = UUID.randomUUID();
        AgentRecord record = new AgentRecord(id, request.name(), request.hostname(), request.address(), request.version(),
                request.cpuCount(), request.memoryBytes(), request.tags() == null ? Map.of() : Map.copyOf(request.tags()),
                now, now, AgentStatus.ONLINE, tokenHash);
        AgentManagement state = new AgentManagement(true, null, false, null, now);
        persist(record, state);
        agents.put(id, record);
        management.put(id, state);
        return new RegistrationResult(id, agentToken, now);
    }

    private static boolean sameIdentity(AgentRecord agent, RegistrationRequest request) {
        return Objects.equals(agent.name(), request.name())
                && Objects.equals(agent.hostname(), request.hostname())
                && Objects.equals(agent.address(), request.address());
    }

    public synchronized AgentRecord heartbeat(UUID id, String token, HeartbeatRequest request) {
        ensureLoaded();
        AgentRecord current = authenticate(id, token);
        Map<String, String> tags = request.tags() == null || request.tags().isEmpty()
                ? current.tags() : Map.copyOf(request.tags());
        boolean recoveredBusy = current.status() == AgentStatus.BUSY && recoveredBusyAgents.contains(id);
        boolean activeRunReported = activeRuns(request.tags()) > 0;
        AgentStatus status = current.status() == AgentStatus.BUSY && (!recoveredBusy || activeRunReported)
                ? AgentStatus.BUSY : AgentStatus.ONLINE;
        AgentRecord updated = new AgentRecord(current.id(), current.name(), current.hostname(), current.address(),
                request.version() == null ? current.version() : request.version(),
                request.cpuCount() <= 0 ? current.cpuCount() : request.cpuCount(),
                request.memoryBytes() <= 0 ? current.memoryBytes() : request.memoryBytes(), tags,
                current.registeredAt(), Instant.now(), status, current.agentTokenHash());
        AgentManagement state = management.getOrDefault(id, AgentManagement.defaults());
        AgentManagement updatedState = state;
        if (state.updateRequested() && state.desiredVersion() != null
                && state.desiredVersion().equals(updated.version())) {
            updatedState = new AgentManagement(state.enabled(), state.desiredVersion(), false,
                    Instant.now(), state.changedAt());
        }
        persist(updated, updatedState);
        agents.put(id, updated);
        management.put(id, updatedState);
        if (recoveredBusy && !activeRunReported) recoveredBusyAgents.remove(id);
        return updated;
    }

    public List<AgentView> list() {
        ensureLoaded();
        Instant now = Instant.now();
        return agents.values().stream().map(agent -> {
            AgentManagement state = management.getOrDefault(agent.id(), AgentManagement.defaults());
            boolean online = Duration.between(agent.lastSeenAt(), now).compareTo(Duration.ofSeconds(45)) <= 0;
            AgentStatus status = !state.enabled() ? AgentStatus.DISABLED
                    : online ? agent.status() : AgentStatus.OFFLINE;
            return new AgentView(agent.id(), agent.name(), agent.hostname(), agent.address(), agent.version(),
                    agent.cpuCount(), agent.memoryBytes(), agent.tags(), agent.registeredAt(), agent.lastSeenAt(),
                    status, state.enabled(), state.desiredVersion(), state.updateRequested(),
                    state.updateCompletedAt(), state.changedAt());
        }).sorted(Comparator.comparing(AgentView::name,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    public AgentRecord authenticate(UUID id, String token) {
        ensureLoaded();
        AgentRecord agent = agents.get(id);
        if (agent == null) throw new IllegalArgumentException("Agent not found");
        AgentManagement state = management.getOrDefault(id, AgentManagement.defaults());
        if (!state.enabled()) throw new SecurityException("Agent is disabled");
        if (!matchesToken(agent.agentTokenHash(), token)) throw new SecurityException("Invalid agent token");
        return agent;
    }

    public synchronized AgentView setEnabled(UUID id, boolean enabled) {
        ensureLoaded();
        requirePresent(id);
        AgentManagement current = management.getOrDefault(id, AgentManagement.defaults());
        AgentManagement updated = new AgentManagement(enabled, current.desiredVersion(), current.updateRequested(),
                current.updateCompletedAt(), Instant.now());
        persist(agents.get(id), updated);
        management.put(id, updated);
        return view(id);
    }

    public synchronized AgentView requestUpdate(UUID id, String desiredVersion) {
        ensureLoaded();
        requirePresent(id);
        if (desiredVersion == null || desiredVersion.isBlank()) {
            throw new IllegalArgumentException("Desired version is required");
        }
        AgentManagement current = management.getOrDefault(id, AgentManagement.defaults());
        AgentManagement updated = new AgentManagement(current.enabled(), desiredVersion.trim(), true,
                null, Instant.now());
        persist(agents.get(id), updated);
        management.put(id, updated);
        return view(id);
    }

    public synchronized void revokeIdentity(UUID id) {
        ensureLoaded();
        requirePresent(id);
        if (repository != null) repository.delete(id);
        agents.remove(id);
        management.remove(id);
        recoveredBusyAgents.remove(id);
    }

    public void requireOnline(UUID id) {
        ensureLoaded();
        AgentRecord agent = agents.get(id);
        if (agent == null) throw new IllegalArgumentException("Agent not found: " + id);
        AgentManagement state = management.getOrDefault(id, AgentManagement.defaults());
        if (!state.enabled()) throw new IllegalStateException("Agent is disabled: " + id);
        if (Duration.between(agent.lastSeenAt(), Instant.now()).compareTo(Duration.ofSeconds(45)) > 0) {
            throw new IllegalStateException("Agent is offline: " + id);
        }
    }

    public void requireCapability(UUID id, String capability) {
        ensureLoaded();
        requireOnline(id);
        AgentRecord agent = agents.get(id);
        String capabilities = agent.tags().getOrDefault("capabilities", "S3");
        boolean supported = Arrays.stream(capabilities.split(","))
                .map(String::trim).anyMatch(value -> value.equalsIgnoreCase(capability));
        if (!supported) throw new IllegalStateException("Agent " + id + " does not support " + capability);
    }

    public synchronized void reserve(UUID id, String capability) {
        ensureLoaded();
        if (capability == null || capability.isBlank()) requireOnline(id);
        else requireCapability(id, capability);
        AgentRecord current = agents.get(id);
        if (current.status() == AgentStatus.BUSY) {
            throw new IllegalStateException("Agent is already busy: " + id);
        }
        markBusy(id, true);
    }

    public synchronized void release(UUID id) {
        ensureLoaded();
        if (agents.containsKey(id)) markBusy(id, false);
    }

    public synchronized void markBusy(UUID id, boolean busy) {
        ensureLoaded();
        AgentRecord current = agents.get(id);
        if (current == null) throw new IllegalArgumentException("Agent not found");
        AgentRecord updated = new AgentRecord(current.id(), current.name(), current.hostname(), current.address(),
                current.version(), current.cpuCount(), current.memoryBytes(), current.tags(), current.registeredAt(),
                current.lastSeenAt(), busy ? AgentStatus.BUSY : AgentStatus.ONLINE, current.agentTokenHash());
        persist(updated, management.getOrDefault(id, AgentManagement.defaults()));
        agents.put(id, updated);
        recoveredBusyAgents.remove(id);
    }

    private AgentView view(UUID id) {
        return list().stream().filter(agent -> agent.id().equals(id)).findFirst().orElseThrow();
    }

    private void requirePresent(UUID id) {
        if (!agents.containsKey(id)) throw new IllegalArgumentException("Agent not found");
    }

    private void persist(AgentRecord agent, AgentManagement state) {
        if (repository == null) return;
        repository.save(agent, state);
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            for (AgentRegistryRepository.StoredAgent stored : repository.findAll()) {
                AgentRecord record = stored.agent();
                if (record.status() == AgentStatus.BUSY) {
                    recoveredBusyAgents.add(record.id());
                }
                agents.put(record.id(), record);
                management.put(record.id(), stored.management());
            }
            loaded = true;
        }
    }

    private static String newAgentToken() {
        return UUID.randomUUID() + "." + UUID.randomUUID();
    }

    private static int activeRuns(Map<String, String> tags) {
        if (tags == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(tags.getOrDefault("runtime.activeRuns", "0")));
        } catch (NumberFormatException invalidTelemetry) {
            return 0;
        }
    }

    private static String hashToken(String token) {
        return HexFormat.of().formatHex(digest(token));
    }

    private static boolean matchesToken(String expectedHash, String token) {
        if (expectedHash == null || expectedHash.isBlank() || token == null || token.isBlank()) return false;
        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(expectedHash), digest(token));
        } catch (IllegalArgumentException invalidHash) {
            return false;
        }
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public enum AgentStatus { ONLINE, OFFLINE, BUSY, DISABLED }

    public record RegistrationRequest(String name, String hostname, String address, String version,
                                      int cpuCount, long memoryBytes, Map<String, String> tags) { }

    public record HeartbeatRequest(String version, int cpuCount, long memoryBytes, Map<String, String> tags) { }

    public record RegistrationResult(UUID agentId, String agentToken, Instant registeredAt) { }

    public record AgentRecord(UUID id, String name, String hostname, String address, String version,
                              int cpuCount, long memoryBytes, Map<String, String> tags, Instant registeredAt,
                              Instant lastSeenAt, AgentStatus status, @JsonIgnore String agentTokenHash) { }

    public record AgentView(UUID id, String name, String hostname, String address, String version,
                            int cpuCount, long memoryBytes, Map<String, String> tags, Instant registeredAt,
                            Instant lastSeenAt, AgentStatus status, boolean enabled, String desiredVersion,
                            boolean updateRequested, Instant updateCompletedAt, Instant managementChangedAt) { }

    record AgentManagement(boolean enabled, String desiredVersion, boolean updateRequested,
                           Instant updateCompletedAt, Instant changedAt) {
        static AgentManagement defaults() {
            return new AgentManagement(true, null, false, null, Instant.EPOCH);
        }
    }
}
