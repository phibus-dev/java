package dev.phibus.s3.distributed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AgentRegistryRepository {
    private static final TypeReference<Map<String, String>> TAGS_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    AgentRegistryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<StoredAgent> findAll() {
        return jdbc.query("""
                SELECT id, name, hostname, address, version, cpu_count, memory_bytes, status, tags_json,
                       registered_at, last_seen_at, agent_token_hash, enabled, desired_version,
                       update_requested, update_completed_at, management_changed_at
                  FROM agent
                 ORDER BY registered_at, id
                """, this::map);
    }

    void save(AgentRegistry.AgentRecord agent, AgentRegistry.AgentManagement management) {
        jdbc.update("""
                INSERT INTO agent(id, name, hostname, address, version, cpu_count, memory_bytes, status, tags_json,
                                  registered_at, last_seen_at, agent_token_hash, enabled, desired_version,
                                  update_requested, update_completed_at, management_changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name=EXCLUDED.name, hostname=EXCLUDED.hostname, address=EXCLUDED.address,
                    version=EXCLUDED.version, cpu_count=EXCLUDED.cpu_count,
                    memory_bytes=EXCLUDED.memory_bytes, status=EXCLUDED.status, tags_json=EXCLUDED.tags_json,
                    registered_at=EXCLUDED.registered_at, last_seen_at=EXCLUDED.last_seen_at,
                    agent_token_hash=EXCLUDED.agent_token_hash, enabled=EXCLUDED.enabled,
                    desired_version=EXCLUDED.desired_version, update_requested=EXCLUDED.update_requested,
                    update_completed_at=EXCLUDED.update_completed_at,
                    management_changed_at=EXCLUDED.management_changed_at
                """, agent.id(), agent.name(), agent.hostname(), agent.address(), agent.version(), agent.cpuCount(),
                agent.memoryBytes(), agent.status().name(), writeTags(agent.tags()), timestamp(agent.registeredAt()),
                timestamp(agent.lastSeenAt()), agent.agentTokenHash(), management.enabled(),
                management.desiredVersion(), management.updateRequested(), timestamp(management.updateCompletedAt()),
                timestamp(management.changedAt()));
    }

    void delete(UUID id) {
        jdbc.update("DELETE FROM agent WHERE id=?", id);
    }

    private StoredAgent map(ResultSet result, int rowNumber) throws SQLException {
        AgentRegistry.AgentRecord agent = new AgentRegistry.AgentRecord(
                result.getObject("id", UUID.class), result.getString("name"), result.getString("hostname"),
                result.getString("address"), result.getString("version"), result.getInt("cpu_count"),
                result.getLong("memory_bytes"), readTags(result.getString("tags_json")),
                instant(result.getTimestamp("registered_at")), instant(result.getTimestamp("last_seen_at")),
                AgentRegistry.AgentStatus.valueOf(result.getString("status")),
                result.getString("agent_token_hash"));
        AgentRegistry.AgentManagement management = new AgentRegistry.AgentManagement(
                result.getBoolean("enabled"), result.getString("desired_version"),
                result.getBoolean("update_requested"), instant(result.getTimestamp("update_completed_at")),
                instant(result.getTimestamp("management_changed_at")));
        return new StoredAgent(agent, management);
    }

    private Map<String, String> readTags(String json) throws SQLException {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> tags = objectMapper.readValue(json, TAGS_TYPE);
            return tags == null || tags.isEmpty() ? Map.of() : Map.copyOf(tags);
        } catch (JsonProcessingException error) {
            throw new SQLException("Cannot read persisted agent tags", error);
        }
    }

    private String writeTags(Map<String, String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? Map.of() : tags);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot persist agent tags", error);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    record StoredAgent(AgentRegistry.AgentRecord agent, AgentRegistry.AgentManagement management) { }
}
