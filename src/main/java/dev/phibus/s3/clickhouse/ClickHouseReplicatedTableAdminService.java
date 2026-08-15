package dev.phibus.s3.clickhouse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseReplicatedTableAdminService {
    private final ClickHouseProfileService profiles;

    public ClickHouseReplicatedTableAdminService(ClickHouseProfileService profiles) {
        this.profiles = profiles;
    }

    public ProvisionResult provision(ProvisionRequest request) {
        validate(request);
        ClickHouseProfileService.Profile profile = profiles.get(request.profileId());
        String table = table(request.table());
        String keeperPath = keeperPath(request.keeperPath());
        String replicaMacro = replicaMacro(request.replicaMacro());
        List<NodeResult> nodes = new ArrayList<>();
        for (String endpoint : profile.endpoints()) {
            try (Connection connection = profiles.open(request.profileId(), endpoint);
                 Statement statement = connection.createStatement()) {
                if (request.dropExisting()) {
                    statement.execute("DROP TABLE IF EXISTS " + table + " SYNC");
                }
                String engine = "ReplicatedMergeTree('" + keeperPath + "', '" + replicaMacro + "')";
                statement.execute("CREATE TABLE IF NOT EXISTS " + table
                        + " (event_time DateTime64(3), sequence UInt64, payload String) ENGINE = " + engine
                        + " ORDER BY sequence");
                nodes.add(inspect(connection, endpoint, profile.database(), table));
            } catch (Exception e) {
                nodes.add(new NodeResult(endpoint, false, null, null, rootMessage(e)));
            }
        }
        boolean success = !nodes.isEmpty() && nodes.stream().allMatch(NodeResult::success);
        return new ProvisionResult(request.profileId(), table, keeperPath, replicaMacro, success, List.copyOf(nodes));
    }

    private static NodeResult inspect(Connection connection, String endpoint, String database, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT engine, engine_full FROM system.tables WHERE database=? AND name=?")) {
            ps.setString(1, database);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new NodeResult(endpoint, false, null, null, "Table was not created");
                String engine = rs.getString(1);
                String full = rs.getString(2);
                boolean replicated = engine != null && engine.startsWith("Replicated");
                return new NodeResult(endpoint, replicated, engine, full, replicated ? null : "Table is not Replicated*");
            }
        }
    }

    private static void validate(ProvisionRequest request) {
        if (request == null || request.profileId() == null) throw new IllegalArgumentException("profileId is required");
        table(request.table());
        keeperPath(request.keeperPath());
        replicaMacro(request.replicaMacro());
    }

    private static String table(String value) {
        String v = value == null ? "" : value.trim();
        if (!v.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid ClickHouse table name");
        return v;
    }

    private static String keeperPath(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() > 512 || !v.matches("/[A-Za-z0-9_./{}\\-]+")) {
            throw new IllegalArgumentException("Invalid Keeper path");
        }
        return v;
    }

    private static String replicaMacro(String value) {
        String v = value == null || value.isBlank() ? "{replica}" : value.trim();
        if (v.length() > 128 || !v.matches("[A-Za-z0-9_.{}\\-]+")) {
            throw new IllegalArgumentException("Invalid replica macro");
        }
        return v;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record ProvisionRequest(UUID profileId, String table, String keeperPath, String replicaMacro, boolean dropExisting) { }
    public record NodeResult(String endpoint, boolean success, String engine, String engineFull, String error) { }
    public record ProvisionResult(UUID profileId, String table, String keeperPath, String replicaMacro,
                                  boolean success, List<NodeResult> nodes) { }
}
