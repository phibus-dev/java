package dev.phibus.s3.kafka;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaM2RunService {
    private static final String RUN_HEADER = "evo-run";
    private static final String SEQ_HEADER = "evo-seq";
    private static final String SENT_NANOS_HEADER = "evo-sent-nanos";

    private final KafkaProfileService profiles;
    private final KafkaConnectionService connections;
    private final JdbcTemplate jdbc;
    private final Executor executor;
    private final Map<UUID, Snapshot> runtime = new ConcurrentHashMap<>();

    public KafkaM2RunService(KafkaProfileService profiles, KafkaConnectionService connections,
                             JdbcTemplate jdbc, @Qualifier("testExecutor") Executor executor) {
        this.profiles = profiles;
        this.connections = connections;
        this.jdbc = jdbc;
        this.executor = executor;
    }

    public Snapshot startConsumer(ConsumerRequest request, String initiator) {
        KafkaProfileService.Profile profile = resolveProfile(request.profileId());
        String topic = required(request.topic() == null ? profile.defaultTopic() : request.topic(), "Kafka topic is required");
        UUID id = UUID.randomUUID(); Instant started = Instant.now();
        Snapshot initial = Snapshot.consumer(id, profile.id(), topic, request.groupId(), started);
        runtime.put(id, initial);
        jdbc.update("""
            INSERT INTO kafka_test_run(id,profile_id,test_type,topic,status,started_at,consumer_group,consumer_threads,
              requested_messages,message_size_bytes,initiator,config_json)
            VALUES (?,?, 'KAFKA_CONSUMER', ?, 'RUNNING', ?, ?, ?, ?, 0, ?, ?)
            """, id, profile.id(), topic, started, defaultValue(request.groupId(), "evo-snt-consumer-" + id),
                Math.max(1, request.consumerThreads()), request.targetMessages(), initiator, request.toString());
        executor.execute(() -> runConsumer(initial, request, profile));
        return initial;
    }

    public Snapshot startE2e(E2eRequest request, String initiator) {
        KafkaProfileService.Profile profile = resolveProfile(request.profileId());
        String topic = required(request.topic() == null ? profile.defaultTopic() : request.topic(), "Kafka topic is required");
        if (request.messageCount() <= 0 || request.messageCount() > 10_000_000L)
            throw new IllegalArgumentException("E2E messageCount must be between 1 and 10000000");
        UUID id = UUID.randomUUID(); Instant started = Instant.now();
        Snapshot initial = Snapshot.e2e(id, profile.id(), topic, started, request.messageCount());
        runtime.put(id, initial);
        jdbc.update("""
            INSERT INTO kafka_test_run(id,profile_id,test_type,topic,status,started_at,consumer_group,consumer_threads,
              requested_messages,message_size_bytes,producer_threads,initiator,config_json)
            VALUES (?,?, 'KAFKA_E2E', ?, 'RUNNING', ?, ?, 1, ?, ?, 1, ?, ?)
            """, id, profile.id(), topic, started, "evo-snt-e2e-" + id, request.messageCount(),
                Math.max(32, request.messageSizeBytes()), initiator, request.toString());
        executor.execute(() -> runE2e(initial, request, profile));
        return initial;
    }

    public Snapshot get(UUID id) {
        Snapshot live = runtime.get(id);
        if (live != null) return live;
        return jdbc.query("SELECT * FROM kafka_test_run WHERE id=?", (rs,row) -> new Snapshot(
                rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class), rs.getString("test_type"),
                rs.getString("topic"), rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getLong("sent_messages"), rs.getLong("consumed_messages"), rs.getLong("sent_bytes"), rs.getLong("consumed_bytes"),
                rs.getDouble("throughput_messages_sec"), rs.getDouble("consumer_messages_sec"), rs.getDouble("consumer_lag"),
                rs.getLong("max_partition_lag"), rs.getLong("missing_messages"), rs.getLong("duplicate_messages"),
                rs.getLong("out_of_order_messages"), rs.getLong("corrupted_messages"), rs.getString("consistency_status"),
                rs.getDouble("e2e_latency_avg_ms"), rs.getDouble("e2e_latency_p50_ms"), rs.getDouble("e2e_latency_p95_ms"),
                rs.getDouble("e2e_latency_p99_ms"), rs.getDouble("e2e_latency_max_ms"), rs.getLong("error_count"), rs.getString("error_message")), id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Kafka run not found: " + id));
    }

    public List<Snapshot> history(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        return jdbc.query("SELECT id FROM kafka_test_run WHERE test_type IN ('KAFKA_CONSUMER','KAFKA_E2E') ORDER BY started_at DESC LIMIT ?",
                (rs,row) -> get(rs.getObject("id", UUID.class)), bounded);
    }

    private void runConsumer(Snapshot initial, ConsumerRequest request, KafkaProfileService.Profile profile) {
        UUID id = initial.id(); long startedNanos = System.nanoTime(); AtomicLong consumed = new AtomicLong(); AtomicLong bytes = new AtomicLong();
        long errors = 0; String error = null; long totalLag = 0; long maxLag = 0;
        String group = defaultValue(request.groupId(), "evo-snt-consumer-" + id);
        Properties props = consumerProperties(profile, group, request);
        try (KafkaConsumer<byte[],byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(initial.topic()));
            long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(1, request.durationSeconds())).toNanos();
            while (System.nanoTime() < deadline && (request.targetMessages() <= 0 || consumed.get() < request.targetMessages())) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<byte[],byte[]> record : records) {
                    consumed.incrementAndGet(); bytes.addAndGet(record.serializedValueSize());
                }
                if (!consumer.assignment().isEmpty()) {
                    Map<TopicPartition,Long> ends = consumer.endOffsets(consumer.assignment()); totalLag = 0; maxLag = 0;
                    for (TopicPartition tp : consumer.assignment()) {
                        long lag = Math.max(0, ends.getOrDefault(tp, 0L) - consumer.position(tp)); totalLag += lag; maxLag = Math.max(maxLag, lag);
                    }
                }
                double seconds = Math.max(0.001, (System.nanoTime() - startedNanos) / 1_000_000_000d);
                runtime.put(id, initial.withProgress("RUNNING", consumed.get(), bytes.get(), consumed.get()/seconds, totalLag, maxLag));
            }
        } catch (Exception e) { errors = 1; error = rootMessage(e); }
        double seconds = Math.max(0.001, (System.nanoTime() - startedNanos) / 1_000_000_000d);
        String status = errors == 0 ? "COMPLETED" : "FAILED"; Instant finished = Instant.now();
        Snapshot done = initial.finishConsumer(status, finished, consumed.get(), bytes.get(), consumed.get()/seconds, totalLag, maxLag, errors, error);
        runtime.put(id, done); persist(done);
    }

    private void runE2e(Snapshot initial, E2eRequest request, KafkaProfileService.Profile profile) {
        UUID id = initial.id(); String runMarker = id.toString(); int total = Math.toIntExact(request.messageCount());
        BitSet seen = new BitSet(total); Map<Integer,Long> lastByPartition = new HashMap<>(); List<Double> latencies = new ArrayList<>(Math.min(total, 1_000_000));
        AtomicLong produced = new AtomicLong(); AtomicLong consumed = new AtomicLong(); AtomicLong sentBytes = new AtomicLong(); AtomicLong consumedBytes = new AtomicLong();
        AtomicLong duplicates = new AtomicLong(); AtomicLong outOfOrder = new AtomicLong(); AtomicLong corrupted = new AtomicLong(); AtomicLong errors = new AtomicLong();
        String[] error = new String[1]; long startedNanos = System.nanoTime();
        Properties consumerProps = consumerProperties(profile, "evo-snt-e2e-" + id, new ConsumerRequest(profile.id(), initial.topic(), "evo-snt-e2e-" + id, 1, 0, 120, "latest", 500));
        Properties producerProps = producerProperties(profile, request);
        try (KafkaConsumer<byte[],byte[]> consumer = new KafkaConsumer<>(consumerProps); KafkaProducer<byte[],byte[]> producer = new KafkaProducer<>(producerProps)) {
            consumer.subscribe(List.of(initial.topic()));
            consumer.poll(Duration.ofMillis(500));
            byte[] marker = runMarker.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i=0;i<total;i++) {
                byte[] payload = payload(request.messageSizeBytes(), i);
                ProducerRecord<byte[],byte[]> rec = new ProducerRecord<>(initial.topic(), payload);
                rec.headers().add(RUN_HEADER, marker).add(SEQ_HEADER, ByteBuffer.allocate(Long.BYTES).putLong(i).array())
                        .add(SENT_NANOS_HEADER, ByteBuffer.allocate(Long.BYTES).putLong(System.nanoTime()).array());
                producer.send(rec, (metadata, ex) -> { if (ex != null) { errors.incrementAndGet(); error[0] = rootMessage(ex); } else { produced.incrementAndGet(); sentBytes.addAndGet(payload.length); } });
                if (request.targetMessagesPerSec() > 0) throttle(startedNanos, i + 1L, request.targetMessagesPerSec());
            }
            producer.flush();
            long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(5, request.consumeTimeoutSeconds())).toNanos();
            while (System.nanoTime() < deadline && consumed.get() < produced.get()) {
                for (ConsumerRecord<byte[],byte[]> record : consumer.poll(Duration.ofMillis(300))) {
                    Header rh = record.headers().lastHeader(RUN_HEADER); if (rh == null || !runMarker.equals(new String(rh.value(), java.nio.charset.StandardCharsets.UTF_8))) continue;
                    Header sh = record.headers().lastHeader(SEQ_HEADER); Header th = record.headers().lastHeader(SENT_NANOS_HEADER);
                    if (sh == null || th == null || sh.value().length != Long.BYTES || th.value().length != Long.BYTES) { corrupted.incrementAndGet(); continue; }
                    long seq = ByteBuffer.wrap(sh.value()).getLong(); long sent = ByteBuffer.wrap(th.value()).getLong();
                    if (seq < 0 || seq >= total) { corrupted.incrementAndGet(); continue; }
                    int index = (int) seq; if (seen.get(index)) duplicates.incrementAndGet(); else { seen.set(index); consumed.incrementAndGet(); consumedBytes.addAndGet(Math.max(0, record.serializedValueSize())); }
                    Long last = lastByPartition.put(record.partition(), seq); if (last != null && seq < last) outOfOrder.incrementAndGet();
                    double latency = Math.max(0, (System.nanoTime() - sent) / 1_000_000d); if (latencies.size() < 1_000_000) latencies.add(latency);
                }
                double seconds = Math.max(0.001, (System.nanoTime()-startedNanos)/1_000_000_000d);
                runtime.put(id, initial.withE2eProgress(produced.get(), consumed.get(), sentBytes.get(), consumedBytes.get(), produced.get()/seconds,
                        duplicates.get(), outOfOrder.get(), corrupted.get()));
            }
        } catch (Exception e) { errors.incrementAndGet(); error[0] = rootMessage(e); }
        long missing = Math.max(0, produced.get() - consumed.get()); String consistency = missing==0 && duplicates.get()==0 && corrupted.get()==0 ? "PASS" : "FAIL";
        latencies.sort(Comparator.naturalOrder()); double avg = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double p50=p(latencies,.50), p95=p(latencies,.95), p99=p(latencies,.99), max=latencies.isEmpty()?0:latencies.getLast();
        String status = errors.get()>0 ? "FAILED" : "COMPLETED"; Instant finished=Instant.now(); double seconds=Math.max(.001,(System.nanoTime()-startedNanos)/1_000_000_000d);
        Snapshot done=initial.finishE2e(status,finished,produced.get(),consumed.get(),sentBytes.get(),consumedBytes.get(),produced.get()/seconds,missing,
                duplicates.get(),outOfOrder.get(),corrupted.get(),consistency,avg,p50,p95,p99,max,errors.get(),error[0]); runtime.put(id,done); persist(done);
    }

    private Properties consumerProperties(KafkaProfileService.Profile profile, String group, ConsumerRequest request) {
        Properties p = connections.clientProperties(profile); p.put(ConsumerConfig.GROUP_ID_CONFIG, group); p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName()); p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, defaultValue(request.autoOffsetReset(), "latest")); p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Math.max(1, request.maxPollRecords()));
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, profile.clientIdPrefix()+"-consumer-"+UUID.randomUUID()); return p;
    }

    private Properties producerProperties(KafkaProfileService.Profile profile, E2eRequest request) {
        Properties p=connections.clientProperties(profile); p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class.getName()); p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, defaultValue(request.acks(),"all")); p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,String.valueOf(request.enableIdempotence()));
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,defaultValue(request.compressionType(),"none")); p.put(ProducerConfig.CLIENT_ID_CONFIG,profile.clientIdPrefix()+"-e2e-"+UUID.randomUUID()); return p;
    }

    private void persist(Snapshot s) {
        jdbc.update("""
            UPDATE kafka_test_run SET status=?,finished_at=?,duration_ms=?,sent_messages=?,consumed_messages=?,sent_bytes=?,consumed_bytes=?,
              throughput_messages_sec=?,consumer_messages_sec=?,consumer_lag=?,max_partition_lag=?,missing_messages=?,duplicate_messages=?,
              out_of_order_messages=?,corrupted_messages=?,consistency_status=?,e2e_latency_avg_ms=?,e2e_latency_p50_ms=?,e2e_latency_p95_ms=?,
              e2e_latency_p99_ms=?,e2e_latency_max_ms=?,error_count=?,error_message=? WHERE id=?
            """, s.status(), s.finishedAt(), s.finishedAt()==null?null:Duration.between(s.startedAt(),s.finishedAt()).toMillis(), s.sentMessages(), s.consumedMessages(),
                s.sentBytes(), s.consumedBytes(), s.producerMessagesPerSec(), s.consumerMessagesPerSec(), s.consumerLag(), s.maxPartitionLag(), s.missingMessages(),
                s.duplicateMessages(), s.outOfOrderMessages(), s.corruptedMessages(), s.consistencyStatus(), s.e2eLatencyAvgMs(), s.e2eLatencyP50Ms(), s.e2eLatencyP95Ms(),
                s.e2eLatencyP99Ms(), s.e2eLatencyMaxMs(), s.errorCount(), s.errorMessage(), s.id());
    }

    private KafkaProfileService.Profile resolveProfile(UUID id) { KafkaProfileService.Profile p=id==null?profiles.defaultProfile():profiles.get(id); if(p==null)throw new IllegalArgumentException("Kafka profile is required"); return p; }
    private static byte[] payload(int size,long seq){byte[] b=new byte[Math.max(32,size)];ByteBuffer.wrap(b).putLong(seq);return b;}
    private static double p(List<Double> values,double q){if(values.isEmpty())return 0;int i=(int)Math.ceil(q*values.size())-1;return values.get(Math.max(0,Math.min(i,values.size()-1)));}
    private static void throttle(long started,long sent,double rate){long expected=(long)(sent*1_000_000_000d/rate);long wait=expected-(System.nanoTime()-started);if(wait>0)java.util.concurrent.locks.LockSupport.parkNanos(wait);}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
    private static String defaultValue(String v,String d){return v==null||v.isBlank()?d:v.trim();}
    private static String required(String v,String m){if(v==null||v.isBlank())throw new IllegalArgumentException(m);return v.trim();}

    public record ConsumerRequest(UUID profileId,String topic,String groupId,int consumerThreads,long targetMessages,int durationSeconds,String autoOffsetReset,int maxPollRecords){}
    public record E2eRequest(UUID profileId,String topic,long messageCount,int messageSizeBytes,double targetMessagesPerSec,String acks,String compressionType,boolean enableIdempotence,int consumeTimeoutSeconds){}

    public record Snapshot(UUID id,UUID profileId,String testType,String topic,String status,Instant startedAt,Instant finishedAt,long sentMessages,long consumedMessages,long sentBytes,long consumedBytes,double producerMessagesPerSec,double consumerMessagesPerSec,double consumerLag,long maxPartitionLag,long missingMessages,long duplicateMessages,long outOfOrderMessages,long corruptedMessages,String consistencyStatus,double e2eLatencyAvgMs,double e2eLatencyP50Ms,double e2eLatencyP95Ms,double e2eLatencyP99Ms,double e2eLatencyMaxMs,long errorCount,String errorMessage){
        static Snapshot consumer(UUID id,UUID profile,String topic,String group,Instant started){return new Snapshot(id,profile,"KAFKA_CONSUMER",topic,"RUNNING",started,null,0,0,0,0,0,0,0,0,0,0,0,0,null,0,0,0,0,0,0,null);}
        static Snapshot e2e(UUID id,UUID profile,String topic,Instant started,long requested){return new Snapshot(id,profile,"KAFKA_E2E",topic,"RUNNING",started,null,0,0,0,0,0,0,0,0,requested,0,0,0,"PENDING",0,0,0,0,0,0,null);}
        Snapshot withProgress(String st,long consumed,long bytes,double rate,long lag,long maxLag){return new Snapshot(id,profileId,testType,topic,st,startedAt,null,0,consumed,0,bytes,0,rate,lag,maxLag,0,0,0,0,null,0,0,0,0,0,0,null);}
        Snapshot finishConsumer(String st,Instant f,long consumed,long bytes,double rate,long lag,long maxLag,long errors,String error){return new Snapshot(id,profileId,testType,topic,st,startedAt,f,0,consumed,0,bytes,0,rate,lag,maxLag,0,0,0,0,null,0,0,0,0,0,errors,error);}
        Snapshot withE2eProgress(long sent,long consumed,long sb,long cb,double rate,long dup,long oo,long corr){return new Snapshot(id,profileId,testType,topic,"RUNNING",startedAt,null,sent,consumed,sb,cb,rate,0,0,0,Math.max(0,sent-consumed),dup,oo,corr,"PENDING",0,0,0,0,0,0,null);}
        Snapshot finishE2e(String st,Instant f,long sent,long consumed,long sb,long cb,double rate,long missing,long dup,long oo,long corr,String consistency,double avg,double p50,double p95,double p99,double max,long errors,String error){return new Snapshot(id,profileId,testType,topic,st,startedAt,f,sent,consumed,sb,cb,rate,0,0,0,missing,dup,oo,corr,consistency,avg,p50,p95,p99,max,errors,error);}
    }
}
