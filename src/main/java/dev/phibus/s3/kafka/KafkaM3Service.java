package dev.phibus.s3.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaM3Service {
    private static final long TIMEOUT_SECONDS = 15;

    private final KafkaProfileService profiles;
    private final KafkaConnectionService connections;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Map<UUID, M3Run> runtime = new ConcurrentHashMap<>();

    public KafkaM3Service(KafkaProfileService profiles,
                          KafkaConnectionService connections,
                          JdbcTemplate jdbc,
                          ObjectMapper objectMapper,
                          @Qualifier("testExecutor") Executor executor) {
        this.profiles = profiles;
        this.connections = connections;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public KraftHealth kraftHealth(UUID profileId) {
        KafkaProfileService.Profile profile = resolveProfile(profileId);
        KafkaConnectionService.ClusterInfo info = connections.check(profile.id());
        boolean controllerAvailable = info.controllerId() != null;
        return new KraftHealth(
                info.clusterId(),
                info.controllerId(),
                info.nodes().size(),
                controllerAvailable,
                controllerAvailable && !info.nodes().isEmpty() ? "HEALTHY" : "DEGRADED",
                info.nodes());
    }

    public M3Run startReplication(ReplicationRequest request, String initiator) {
        KafkaProfileService.Profile profile = resolveProfile(request.profileId());
        String topic = required(request.topic() == null ? profile.defaultTopic() : request.topic(), "Kafka topic is required");
        UUID id = UUID.randomUUID();
        Instant started = Instant.now();
        M3Run initial = M3Run.running(id, profile.id(), "KAFKA_REPLICATION", topic, started);
        runtime.put(id, initial);
        insert(initial, initiator);
        try {
            executor.execute(() -> runReplication(initial, profile));
            return initial;
        } catch (RuntimeException error) {
            M3Run failed = initial.failed("Cannot schedule Kafka replication test: " + rootMessage(error));
            runtime.put(id, failed);
            persist(failed);
            return failed;
        }
    }

    public M3Run startScaling(ScalingRequest request, String initiator) {
        KafkaProfileService.Profile profile = resolveProfile(request.profileId());
        List<Integer> points = normalizePartitionPoints(request.partitionCounts());
        if (request.messagesPerPoint() <= 0 || request.messagesPerPoint() > 5_000_000L) {
            throw new IllegalArgumentException("messagesPerPoint must be between 1 and 5000000");
        }
        if (request.messageSizeBytes() <= 0 || request.messageSizeBytes() > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("messageSizeBytes must be between 1 and 16777216");
        }
        UUID id = UUID.randomUUID();
        Instant started = Instant.now();
        M3Run initial = M3Run.running(id, profile.id(), "KAFKA_PARTITION_SCALING", null, started);
        runtime.put(id, initial);
        insert(initial, initiator);
        try {
            executor.execute(() -> runScaling(initial, profile, request, points));
            return initial;
        } catch (RuntimeException error) {
            M3Run failed = initial.failed("Cannot schedule Kafka partition scaling test: " + rootMessage(error));
            runtime.put(id, failed);
            persist(failed);
            return failed;
        }
    }

    public M3Run get(UUID id) {
        M3Run live = runtime.get(id);
        if (live != null) return live;
        return jdbc.query("SELECT * FROM kafka_m3_run WHERE id=?", (rs, row) -> new M3Run(
                rs.getObject("id", UUID.class),
                rs.getObject("profile_id", UUID.class),
                rs.getString("test_type"),
                rs.getString("topic"),
                rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getObject("broker_count", Integer.class),
                rs.getObject("controller_id", Integer.class),
                rs.getObject("partition_count", Integer.class),
                rs.getLong("replica_count"),
                rs.getLong("isr_count"),
                rs.getLong("under_replicated_partitions"),
                rs.getLong("offline_partitions"),
                rs.getLong("min_isr_violations"),
                readPoints(rs.getString("scaling_json")),
                rs.getString("error_message")), id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Kafka M3 run not found: " + id));
    }

    public List<M3Run> history(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        return jdbc.query("SELECT id FROM kafka_m3_run ORDER BY started_at DESC LIMIT ?",
                (rs, row) -> get(rs.getObject("id", UUID.class)), bounded);
    }

    private void runReplication(M3Run initial, KafkaProfileService.Profile profile) {
        try (AdminClient admin = AdminClient.create(connections.clientProperties(profile))) {
            TopicDescription description = admin.describeTopics(List.of(initial.topic()))
                    .allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(initial.topic());
            if (description == null) throw new IllegalStateException("Topic not found: " + initial.topic());

            int minIsr = topicMinIsr(admin, initial.topic());
            long replicas = 0;
            long isr = 0;
            long underReplicated = 0;
            long offline = 0;
            long minIsrViolations = 0;
            for (TopicPartitionInfo partition : description.partitions()) {
                int replicaCount = partition.replicas().size();
                int isrCount = partition.isr().size();
                replicas += replicaCount;
                isr += isrCount;
                if (isrCount < replicaCount) underReplicated++;
                if (partition.leader() == null) offline++;
                if (isrCount < minIsr) minIsrViolations++;
            }
            KafkaConnectionService.ClusterInfo cluster = connections.check(profile.id());
            M3Run done = initial.completed(
                    cluster.nodes().size(), cluster.controllerId(), description.partitions().size(), replicas, isr,
                    underReplicated, offline, minIsrViolations, List.of());
            runtime.put(initial.id(), done);
            persist(done);
        } catch (Exception e) {
            fail(initial, e);
        }
    }

    private void runScaling(M3Run initial, KafkaProfileService.Profile profile,
                            ScalingRequest request, List<Integer> points) {
        List<ScalingPoint> results = new ArrayList<>();
        try (AdminClient admin = AdminClient.create(connections.clientProperties(profile))) {
            KafkaConnectionService.ClusterInfo cluster = connections.check(profile.id());
            short replicationFactor = (short) Math.max(1, Math.min(
                    request.replicationFactor() <= 0 ? Math.min(3, cluster.nodes().size()) : request.replicationFactor(),
                    cluster.nodes().size()));
            for (int partitions : points) {
                String topic = "evo-m3-" + initial.id().toString().substring(0, 8) + "-p" + partitions;
                try {
                    admin.createTopics(List.of(new NewTopic(topic, partitions, replicationFactor)))
                            .all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    ScalingPoint point = benchmarkTopic(profile, topic, partitions, replicationFactor, request);
                    results.add(point);
                    runtime.put(initial.id(), initial.withScaling(results));
                } finally {
                    try {
                        admin.deleteTopics(List.of(topic)).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // A failed cleanup is intentionally non-fatal; the topic name contains the run id for manual cleanup.
                    }
                }
            }
            M3Run done = initial.completed(cluster.nodes().size(), cluster.controllerId(), null,
                    0, 0, 0, 0, 0, results);
            runtime.put(initial.id(), done);
            persist(done);
        } catch (Exception e) {
            fail(initial.withScaling(results), e);
        }
    }

    private ScalingPoint benchmarkTopic(KafkaProfileService.Profile profile, String topic, int partitions,
                                        short replicationFactor, ScalingRequest request) throws Exception {
        Properties props = connections.clientProperties(profile);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, request.acks() == null || request.acks().isBlank() ? "all" : request.acks());
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,
                request.compressionType() == null || request.compressionType().isBlank() ? "none" : request.compressionType());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, profile.clientIdPrefix() + "-m3-scaling-" + partitions);

        byte[] payload = new byte[request.messageSizeBytes()];
        AtomicLong completed = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        long started = System.nanoTime();
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            for (long i = 0; i < request.messagesPerPoint(); i++) {
                producer.send(new ProducerRecord<>(topic, payload), (metadata, exception) -> {
                    if (exception == null) completed.incrementAndGet();
                    else errors.incrementAndGet();
                });
            }
            producer.flush();
        }
        double seconds = Math.max(0.001, (System.nanoTime() - started) / 1_000_000_000d);
        double messagesPerSecond = completed.get() / seconds;
        double mibPerSecond = completed.get() * (double) request.messageSizeBytes() / 1024d / 1024d / seconds;
        return new ScalingPoint(partitions, replicationFactor, completed.get(), errors.get(), messagesPerSecond, mibPerSecond, seconds);
    }

    private int topicMinIsr(AdminClient admin, String topic) throws Exception {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Config config = admin.describeConfigs(List.of(resource)).all()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(resource);
        if (config == null || config.get("min.insync.replicas") == null) return 1;
        try {
            return Math.max(1, Integer.parseInt(config.get("min.insync.replicas").value()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private void insert(M3Run run, String initiator) {
        jdbc.update("INSERT INTO kafka_m3_run(id,profile_id,test_type,topic,status,started_at,initiator) VALUES (?,?,?,?,?,?,?)",
                run.id(), run.profileId(), run.testType(), run.topic(), run.status(), toOffsetDateTime(run.startedAt()), initiator);
    }

    private void persist(M3Run run) {
        jdbc.update("""
                UPDATE kafka_m3_run SET status=?,finished_at=?,duration_ms=?,broker_count=?,controller_id=?,partition_count=?,
                replica_count=?,isr_count=?,under_replicated_partitions=?,offline_partitions=?,min_isr_violations=?,scaling_json=?,error_message=?
                WHERE id=?
                """,
                run.status(), toOffsetDateTime(run.finishedAt()), run.finishedAt() == null ? null : Duration.between(run.startedAt(), run.finishedAt()).toMillis(),
                run.brokerCount(), run.controllerId(), run.partitionCount(), run.replicaCount(), run.isrCount(),
                run.underReplicatedPartitions(), run.offlinePartitions(), run.minIsrViolations(), writePoints(run.scalingPoints()),
                run.errorMessage(), run.id());
    }

    private void fail(M3Run run, Exception e) {
        String message = rootMessage(e);
        M3Run failed = run.failed(message);
        runtime.put(run.id(), failed);
        persist(failed);
    }

    private KafkaProfileService.Profile resolveProfile(UUID id) {
        KafkaProfileService.Profile profile = id == null ? profiles.defaultProfile() : profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("Kafka profile is required");
        return profile;
    }

    private static List<Integer> normalizePartitionPoints(List<Integer> requested) {
        List<Integer> points = requested == null || requested.isEmpty() ? List.of(1, 3, 6, 12) : requested;
        return points.stream().filter(v -> v != null && v > 0 && v <= 10000).distinct().sorted().toList();
    }

    private String writePoints(List<ScalingPoint> points) {
        try {
            return objectMapper.writeValueAsString(points == null ? List.of() : points);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize Kafka scaling result", e);
        }
    }

    private List<ScalingPoint> readPoints(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readerForListOf(ScalingPoint.class).readValue(json);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record ReplicationRequest(UUID profileId, String topic) { }

    public record ScalingRequest(UUID profileId, List<Integer> partitionCounts, long messagesPerPoint,
                                 int messageSizeBytes, int replicationFactor, String acks, String compressionType) { }

    public record ScalingPoint(int partitions, int replicationFactor, long completedMessages, long errorCount,
                               double messagesPerSecond, double mibPerSecond, double durationSeconds) { }

    public record KraftHealth(String clusterId, Integer controllerId, int brokerCount, boolean controllerAvailable,
                              String status, List<KafkaConnectionService.NodeInfo> nodes) { }

    public record M3Run(UUID id, UUID profileId, String testType, String topic, String status,
                        Instant startedAt, Instant finishedAt, Integer brokerCount, Integer controllerId,
                        Integer partitionCount, long replicaCount, long isrCount, long underReplicatedPartitions,
                        long offlinePartitions, long minIsrViolations, List<ScalingPoint> scalingPoints,
                        String errorMessage) {
        static M3Run running(UUID id, UUID profileId, String testType, String topic, Instant startedAt) {
            return new M3Run(id, profileId, testType, topic, "RUNNING", startedAt, null,
                    null, null, null, 0, 0, 0, 0, 0, List.of(), null);
        }

        M3Run withScaling(List<ScalingPoint> points) {
            return new M3Run(id, profileId, testType, topic, "RUNNING", startedAt, null,
                    brokerCount, controllerId, partitionCount, replicaCount, isrCount, underReplicatedPartitions,
                    offlinePartitions, minIsrViolations, List.copyOf(points), null);
        }

        M3Run completed(Integer brokers, Integer controller, Integer partitions, long replicas, long isr,
                        long underReplicated, long offline, long minIsr, List<ScalingPoint> points) {
            return new M3Run(id, profileId, testType, topic, "COMPLETED", startedAt, Instant.now(), brokers,
                    controller, partitions, replicas, isr, underReplicated, offline, minIsr,
                    points == null ? List.of() : List.copyOf(points), null);
        }

        M3Run failed(String error) {
            return new M3Run(id, profileId, testType, topic, "FAILED", startedAt, Instant.now(), brokerCount,
                    controllerId, partitionCount, replicaCount, isrCount, underReplicatedPartitions,
                    offlinePartitions, minIsrViolations, scalingPoints, error);
        }
    }
}
