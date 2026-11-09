package dev.phibus.s3.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.settings.VaultAuthService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.stereotype.Service;

@Service
public class KafkaConnectionService {
    private final KafkaProfileService profiles;
    private final SettingsService settingsService;
    private final VaultAuthService vaultAuthService;

    public KafkaConnectionService(KafkaProfileService profiles, SettingsService settingsService,
                                  VaultAuthService vaultAuthService) {
        this.profiles = profiles;
        this.settingsService = settingsService;
        this.vaultAuthService = vaultAuthService;
    }

    public ClusterInfo check(java.util.UUID profileId) {
        KafkaProfileService.Profile profile = profiles.get(profileId);
        Properties properties = clientProperties(profile);
        try (AdminClient admin = AdminClient.create(properties)) {
            DescribeClusterResult cluster = admin.describeCluster();
            String clusterId = cluster.clusterId().get(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            Node controller = cluster.controller().get(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            List<NodeInfo> nodes = cluster.nodes().get(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS).stream()
                    .sorted(Comparator.comparingInt(Node::id))
                    .map(n -> new NodeInfo(n.id(), n.host(), n.port(), n.rack(), controller != null && n.id() == controller.id()))
                    .toList();
            List<String> topics = new ArrayList<>(admin.listTopics(new ListTopicsOptions().listInternal(false)).names()
                    .get(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
            topics.sort(String.CASE_INSENSITIVE_ORDER);
            return new ClusterInfo(clusterId, controller == null ? null : controller.id(), nodes, topics);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka connection failed: " + rootMessage(e), e);
        }
    }

    public Properties clientProperties(KafkaProfileService.Profile profile) {
        Properties p = new Properties();
        if (profile.customProperties() != null) profile.customProperties().forEach(p::put);
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, profile.bootstrapServers());
        p.put(AdminClientConfig.CLIENT_ID_CONFIG, profile.clientIdPrefix() + "-admin");
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "12000");
        p.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, profile.securityProtocol());
        if (profile.caCertificatePath() != null && !profile.caCertificatePath().isBlank()) {
            try {
                p.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
                p.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, Files.readString(Path.of(profile.caCertificatePath())));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read Kafka CA certificate: " + profile.caCertificatePath(), e);
            }
        }
        if (profile.securityProtocol().startsWith("SASL_")) configureSasl(p, profile);
        return p;
    }

    private void configureSasl(Properties p, KafkaProfileService.Profile profile) {
        String mechanism = profile.saslMechanism() == null || profile.saslMechanism().isBlank()
                ? "SCRAM-SHA-512" : profile.saslMechanism();
        String secret = resolvePassword(profile);
        String loginModule = "PLAIN".equalsIgnoreCase(mechanism)
                ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                : "org.apache.kafka.common.security.scram.ScramLoginModule";
        p.put(SaslConfigs.SASL_MECHANISM, mechanism);
        p.put(SaslConfigs.SASL_JAAS_CONFIG, loginModule + " required username=\"" + escape(profile.username())
                + "\" password=\"" + escape(secret) + "\";");
    }

    private String resolvePassword(KafkaProfileService.Profile profile) {
        String source = profile.credentialsSource() == null ? "NONE" : profile.credentialsSource().toUpperCase();
        if ("PLAIN".equals(source) || "PROFILE".equals(source)) {
            if (!profile.passwordConfigured()) {
                throw new IllegalStateException("Kafka password is not configured in this profile");
            }
            return profiles.profilePassword(profile.id());
        }
        if ("VAULT".equals(source)) {
            JsonNode data = vaultAuthService.readKvV2(settingsService.load().vault(), profile.vaultSecretPath());
            String field = profile.passwordField() == null || profile.passwordField().isBlank() ? "password" : profile.passwordField();
            String value = data.path(field).asText("");
            if (value.isBlank()) throw new IllegalStateException("Vault secret does not contain Kafka password field: " + field);
            return value;
        }
        if ("ENVIRONMENT".equals(source)) {
            String envName = profile.passwordEnv() == null || profile.passwordEnv().isBlank() ? "KAFKA_PASSWORD" : profile.passwordEnv();
            String value = System.getenv(envName);
            if (value == null || value.isBlank()) throw new IllegalStateException("Environment variable " + envName + " is not set");
            return value;
        }
        throw new IllegalStateException("SASL Kafka profile requires PLAIN, VAULT or ENVIRONMENT credentials source; NONE is not valid for SASL");
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record NodeInfo(int id, String host, int port, String rack, boolean controller) { }
    public record ClusterInfo(String clusterId, Integer controllerId, List<NodeInfo> nodes, List<String> topics) { }
}
