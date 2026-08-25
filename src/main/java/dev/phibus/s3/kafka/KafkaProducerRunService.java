package dev.phibus.s3.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerRunService {
    private static final int DEFAULT_MAX_IN_FLIGHT_MESSAGES = 10_000;
    private static final long SNAPSHOT_CACHE_NANOS = 250_000_000L;
    private static final long MAX_LATENCY_NANOS = Duration.ofHours(1).toNanos();
    private final KafkaProfileService profiles;
    private final KafkaConnectionService connections;
    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<UUID, RuntimeState> active = new ConcurrentHashMap<>();

    public KafkaProducerRunService(KafkaProfileService profiles, KafkaConnectionService connections, JdbcTemplate jdbc) {
        this.profiles = profiles;
        this.connections = connections;
        this.jdbc = jdbc;
    }

    public Snapshot start(ProducerRequest request, String initiator) {
        validate(request);
        KafkaProfileService.Profile profile = request.profileId() == null ? profiles.defaultProfile() : profiles.get(request.profileId());
        if (profile == null) throw new IllegalArgumentException("Kafka profile is required");
        String topic = request.topic() == null || request.topic().isBlank() ? profile.defaultTopic() : request.topic().trim();
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("Kafka topic is required");
        UUID id = UUID.randomUUID();
        RuntimeState state = new RuntimeState(id, profile.id(), topic, request, Instant.now());
        active.put(id, state);
        jdbc.update("""
                INSERT INTO kafka_test_run(id, profile_id, topic, status, requested_messages, producer_threads,
                    message_size_bytes, initiator, config_json)
                VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?)
                """, id, profile.id(), topic, request.messageCount(), request.producerThreads(), request.messageSizeBytes(),
                initiator, configText(request));
        Thread.ofVirtual().name("kafka-producer-run-" + id).start(() -> execute(profile, state));
        return state.snapshot();
    }

    public Snapshot get(UUID id) {
        RuntimeState state = active.get(id);
        if (state != null) return state.snapshot();
        List<Snapshot> rows = jdbc.query("""
                SELECT id, profile_id, topic, status, started_at, finished_at, duration_ms,
                       requested_messages, sent_messages, sent_bytes, producer_threads, message_size_bytes,
                       throughput_messages_sec, throughput_mib_sec, latency_avg_ms, latency_p95_ms,
                       latency_p99_ms, latency_max_ms, error_count, retry_count, error_message
                  FROM kafka_test_run WHERE id=?
                """, (rs, row) -> new Snapshot(rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class),
                rs.getString("topic"), rs.getString("status"), rs.getObject("started_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("finished_at") == null ? null : rs.getObject("finished_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getLong("duration_ms"), rs.getLong("requested_messages"), rs.getLong("sent_messages"),
                rs.getLong("sent_bytes"), rs.getInt("producer_threads"), rs.getInt("message_size_bytes"),
                rs.getDouble("throughput_messages_sec"), rs.getDouble("throughput_mib_sec"), rs.getDouble("latency_avg_ms"),
                rs.getDouble("latency_p95_ms"), rs.getDouble("latency_p99_ms"), rs.getDouble("latency_max_ms"),
                rs.getLong("error_count"), rs.getLong("retry_count"), rs.getString("error_message")), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Kafka test run not found: " + id);
        return rows.getFirst();
    }

    public List<Snapshot> history(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                SELECT id, profile_id, topic, status, started_at, finished_at, duration_ms,
                       requested_messages, sent_messages, sent_bytes, producer_threads, message_size_bytes,
                       throughput_messages_sec, throughput_mib_sec, latency_avg_ms, latency_p95_ms,
                       latency_p99_ms, latency_max_ms, error_count, retry_count, error_message
                  FROM kafka_test_run ORDER BY started_at DESC LIMIT ?
                """, (rs, row) -> new Snapshot(rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class),
                rs.getString("topic"), rs.getString("status"), rs.getObject("started_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("finished_at") == null ? null : rs.getObject("finished_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getLong("duration_ms"), rs.getLong("requested_messages"), rs.getLong("sent_messages"),
                rs.getLong("sent_bytes"), rs.getInt("producer_threads"), rs.getInt("message_size_bytes"),
                rs.getDouble("throughput_messages_sec"), rs.getDouble("throughput_mib_sec"), rs.getDouble("latency_avg_ms"),
                rs.getDouble("latency_p95_ms"), rs.getDouble("latency_p99_ms"), rs.getDouble("latency_max_ms"),
                rs.getLong("error_count"), rs.getLong("retry_count"), rs.getString("error_message")), safeLimit);
    }

    private void execute(KafkaProfileService.Profile profile, RuntimeState state) {
        ProducerRequest request = state.request;
        CountDownLatch workers = new CountDownLatch(request.producerThreads());
        AtomicLong sequence = new AtomicLong();
        byte[] payload = payload(request.messageSizeBytes());
        int maxInFlight = effectiveMaxInFlight(request);
        Semaphore inFlight = new Semaphore(maxInFlight);

        try (KafkaProducer<byte[], byte[]> producer = producer(profile, request, state.id)) {
            for (int worker = 0; worker < request.producerThreads(); worker++) {
                final int workerId = worker;
                Thread.ofVirtual().name("kafka-producer-" + workerId).start(() -> {
                    long perThreadRate = request.targetMessagesPerSec() > 0
                            ? Math.max(1, request.targetMessagesPerSec() / request.producerThreads()) : 0;
                    long intervalNanos = perThreadRate > 0 ? 1_000_000_000L / perThreadRate : 0;
                    long nextSend = System.nanoTime();
                    try {
                        while (true) {
                            long index = sequence.getAndIncrement();
                            if (index >= request.messageCount()) break;
                            if (intervalNanos > 0) {
                                long delay = nextSend - System.nanoTime();
                                if (delay > 0) LockSupport.parkNanos(delay);
                                nextSend += intervalNanos;
                            }
                            byte[] key = Long.toString(index).getBytes(StandardCharsets.UTF_8);
                            inFlight.acquire();
                            state.inFlight.incrementAndGet();
                            long started = System.nanoTime();
                            try {
                                producer.send(new ProducerRecord<>(state.topic, key, payload), (metadata, exception) -> {
                                    long latency = System.nanoTime() - started;
                                    try {
                                        if (exception == null) {
                                            state.recordSuccess(payload.length, latency);
                                        } else {
                                            state.recordError(rootMessage(exception));
                                        }
                                    } finally {
                                        state.inFlight.decrementAndGet();
                                        inFlight.release();
                                    }
                                });
                            } catch (Exception e) {
                                state.inFlight.decrementAndGet();
                                inFlight.release();
                                state.recordError(rootMessage(e));
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        state.recordError("Kafka producer worker interrupted");
                    } finally {
                        workers.countDown();
                    }
                });
            }
            workers.await();
            producer.flush();
            state.finish(state.errors.get() == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.recordError("Kafka producer run interrupted");
            state.finish("FAILED");
        } catch (Exception e) {
            state.recordError(rootMessage(e));
            state.finish("FAILED");
        } finally {
            persistFinal(state);
            active.remove(state.id);
        }
    }

    private KafkaProducer<byte[], byte[]> producer(KafkaProfileService.Profile profile, ProducerRequest request, UUID runId) {
        Properties p = connections.clientProperties(profile);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, profile.clientIdPrefix() + "-producer-" + runId);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, request.acks() == null || request.acks().isBlank() ? "all" : request.acks());
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, request.compressionType() == null || request.compressionType().isBlank() ? "none" : request.compressionType());
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(Math.max(1, request.batchSize())));
        p.put(ProducerConfig.LINGER_MS_CONFIG, Long.toString(Math.max(0, request.lingerMs())));
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.toString(request.enableIdempotence()));
        return new KafkaProducer<>(p);
    }

    private void persistFinal(RuntimeState s) {
        Snapshot v = s.snapshot();
        jdbc.update("""
                UPDATE kafka_test_run SET status=?, finished_at=?, duration_ms=?, sent_messages=?, sent_bytes=?,
                    throughput_messages_sec=?, throughput_mib_sec=?, latency_avg_ms=?, latency_p95_ms=?,
                    latency_p99_ms=?, latency_max_ms=?, error_count=?, retry_count=?, error_message=? WHERE id=?
                """, v.status(), java.time.OffsetDateTime.ofInstant(v.finishedAt(), java.time.ZoneOffset.UTC), v.durationMs(),
                v.sentMessages(), v.sentBytes(), v.messagesPerSec(), v.mibPerSec(), v.latencyAvgMs(), v.latencyP95Ms(),
                v.latencyP99Ms(), v.latencyMaxMs(), v.errorCount(), v.retryCount(), v.errorMessage(), v.id());
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'K');
        return bytes;
    }

    private static void validate(ProducerRequest r) {
        if (r == null) throw new IllegalArgumentException("Kafka producer request is required");
        if (r.messageSizeBytes() < 1 || r.messageSizeBytes() > 100 * 1024 * 1024) throw new IllegalArgumentException("messageSizeBytes must be 1..104857600");
        if (r.messageCount() < 1) throw new IllegalArgumentException("messageCount must be positive");
        if (r.producerThreads() < 1 || r.producerThreads() > 256) throw new IllegalArgumentException("producerThreads must be 1..256");
        if (r.maxInFlightMessages() < 0 || r.maxInFlightMessages() > 1_000_000) throw new IllegalArgumentException("maxInFlightMessages must be 0..1000000");
    }

    private static String configText(ProducerRequest r) {
        return "acks=" + r.acks() + ";compression=" + r.compressionType() + ";batchSize=" + r.batchSize()
                + ";lingerMs=" + r.lingerMs() + ";idempotence=" + r.enableIdempotence()
                + ";targetMessagesPerSec=" + r.targetMessagesPerSec()
                + ";maxInFlightMessages=" + effectiveMaxInFlight(r);
    }

    private static int effectiveMaxInFlight(ProducerRequest request) {
        return request.maxInFlightMessages() == 0 ? DEFAULT_MAX_IN_FLIGHT_MESSAGES : request.maxInFlightMessages();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record ProducerRequest(UUID profileId, String topic, int messageSizeBytes, long messageCount,
                                  int producerThreads, String acks, String compressionType, int batchSize,
                                  long lingerMs, boolean enableIdempotence, long targetMessagesPerSec,
                                  int maxInFlightMessages) { }

    public record Snapshot(UUID id, UUID profileId, String topic, String status, Instant startedAt, Instant finishedAt,
                           long durationMs, long requestedMessages, long sentMessages, long sentBytes,
                           int producerThreads, int messageSizeBytes, double messagesPerSec, double mibPerSec,
                           double latencyAvgMs, double latencyP95Ms, double latencyP99Ms, double latencyMaxMs,
                           long errorCount, long retryCount, String errorMessage) { }

    private static final class RuntimeState {
        final UUID id;
        final UUID profileId;
        final String topic;
        final ProducerRequest request;
        final Instant startedAt;
        final AtomicLong sent = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong retries = new AtomicLong();
        final AtomicLong inFlight = new AtomicLong();
        final Recorder latencyRecorder = new Recorder(1, MAX_LATENCY_NANOS, 3);
        final Histogram latencyTotals = new Histogram(1, MAX_LATENCY_NANOS, 3);
        Histogram intervalLatency;
        volatile String status = "RUNNING";
        volatile String errorMessage;
        volatile Instant finishedAt;
        volatile Snapshot cachedSnapshot;
        volatile long cachedSnapshotAtNanos;

        RuntimeState(UUID id, UUID profileId, String topic, ProducerRequest request, Instant startedAt) {
            this.id=id; this.profileId=profileId; this.topic=topic; this.request=request; this.startedAt=startedAt;
        }
        void recordSuccess(long size, long latencyNanos) {
            sent.incrementAndGet();
            bytes.addAndGet(size);
            latencyRecorder.recordValue(Math.max(1, Math.min(MAX_LATENCY_NANOS, latencyNanos)));
        }
        void recordError(String message) { errors.incrementAndGet(); errorMessage=message; }
        synchronized void finish(String newStatus) {
            status=newStatus;
            finishedAt=Instant.now();
            cachedSnapshot=buildSnapshot();
            cachedSnapshotAtNanos=System.nanoTime();
        }
        Snapshot snapshot() {
            Snapshot current = cachedSnapshot;
            if (finishedAt != null && current != null) return current;
            long now = System.nanoTime();
            if (current != null && now - cachedSnapshotAtNanos < SNAPSHOT_CACHE_NANOS) return current;
            synchronized (this) {
                current = cachedSnapshot;
                if (current == null || now - cachedSnapshotAtNanos >= SNAPSHOT_CACHE_NANOS) {
                    current = buildSnapshot();
                    cachedSnapshot=current;
                    cachedSnapshotAtNanos=now;
                }
                return current;
            }
        }
        private Snapshot buildSnapshot() {
            Instant end = finishedAt == null ? Instant.now() : finishedAt;
            long durationMs = Math.max(1, Duration.between(startedAt, end).toMillis());
            long count = sent.get();
            long byteCount = bytes.get();
            double seconds = durationMs / 1000d;
            intervalLatency = latencyRecorder.getIntervalHistogram(intervalLatency);
            latencyTotals.add(intervalLatency);
            long histogramCount = latencyTotals.getTotalCount();
            double avg=0,p95=0,p99=0,max=0;
            if (histogramCount > 0) {
                avg = latencyTotals.getMean() / 1_000_000d;
                p95 = latencyTotals.getValueAtPercentile(95.0) / 1_000_000d;
                p99 = latencyTotals.getValueAtPercentile(99.0) / 1_000_000d;
                max = latencyTotals.getMaxValue() / 1_000_000d;
            }
            return new Snapshot(id, profileId, topic, status, startedAt, finishedAt, durationMs,
                    request.messageCount(), count, byteCount, request.producerThreads(), request.messageSizeBytes(),
                    count/seconds, byteCount/1024d/1024d/seconds, avg, p95, p99, max, errors.get(), retries.get(), errorMessage);
        }
    }}
