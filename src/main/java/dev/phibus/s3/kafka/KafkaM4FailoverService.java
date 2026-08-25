package dev.phibus.s3.kafka;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaM4FailoverService {
    private static final int ADMIN_TIMEOUT_SECONDS = 10;
    private final KafkaProfileService profiles;
    private final KafkaConnectionService connections;
    private final JdbcTemplate jdbc;
    private final Executor executor;
    private final Map<UUID, FailoverRun> runtime = new ConcurrentHashMap<>();

    public KafkaM4FailoverService(KafkaProfileService profiles, KafkaConnectionService connections,
                                  JdbcTemplate jdbc, @Qualifier("testExecutor") Executor executor) {
        this.profiles = profiles;
        this.connections = connections;
        this.jdbc = jdbc;
        this.executor = executor;
    }

    public FailoverRun start(FailoverRequest request, String initiator) {
        KafkaProfileService.Profile profile = request.profileId() == null ? profiles.defaultProfile() : profiles.get(request.profileId());
        if (profile == null) throw new IllegalArgumentException("Kafka profile is required");
        String topic = request.topic() == null || request.topic().isBlank() ? profile.defaultTopic() : request.topic().trim();
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("Kafka topic is required");
        int warmup = Math.max(3, request.warmupSeconds());
        int observe = Math.max(10, request.observationSeconds());
        int size = Math.max(1, Math.min(request.messageSizeBytes(), 16 * 1024 * 1024));
        UUID id = UUID.randomUUID(); Instant started = Instant.now();
        FailoverRun run = FailoverRun.running(id, profile.id(), topic, started, warmup, observe);
        runtime.put(id, run);
        jdbc.update("INSERT INTO kafka_m4_failover_run(id,profile_id,topic,status,started_at,config_json,initiator) VALUES (?,?,?,?,?,?,?)",
                id, profile.id(), topic, "RUNNING", toOffsetDateTime(started), request.toString(), initiator);
        executor.execute(() -> execute(run, profile, request, size, warmup, observe));
        return run;
    }

    public FailoverRun get(UUID id) {
        FailoverRun live = runtime.get(id);
        if (live != null) return live;
        return jdbc.query("SELECT * FROM kafka_m4_failover_run WHERE id=?", (rs,row) -> new FailoverRun(
                rs.getObject("id",UUID.class),rs.getObject("profile_id",UUID.class),rs.getString("topic"),rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toInstant(),
                0,0,rs.getObject("baseline_controller_id",Integer.class),rs.getObject("final_controller_id",Integer.class),
                rs.getLong("controller_changes"),rs.getLong("leader_changes"),rs.getTimestamp("failure_detected_at")==null?null:rs.getTimestamp("failure_detected_at").toInstant(),
                rs.getTimestamp("recovered_at")==null?null:rs.getTimestamp("recovered_at").toInstant(),rs.getObject("recovery_time_ms",Long.class),
                rs.getLong("sent_messages"),rs.getLong("sent_bytes"),rs.getLong("producer_errors"),rs.getDouble("avg_messages_sec"),
                rs.getDouble("min_messages_sec"),rs.getLong("max_under_replicated"),rs.getLong("max_offline_partitions"),
                rs.getString("consistency_status"),rs.getString("error_message")),id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Kafka failover run not found: "+id));
    }

    public List<FailoverRun> history(int limit) {
        return jdbc.query("SELECT id FROM kafka_m4_failover_run ORDER BY started_at DESC LIMIT ?",(rs,row)->get(rs.getObject("id",UUID.class)),Math.max(1,Math.min(limit,500)));
    }

    private void execute(FailoverRun initial, KafkaProfileService.Profile profile, FailoverRequest request, int messageSize, int warmup, int observe) {
        AtomicLong sent = new AtomicLong(); AtomicLong bytes = new AtomicLong(); AtomicLong errors = new AtomicLong();
        long startNanos = System.nanoTime();
        try (AdminClient admin = AdminClient.create(connections.clientProperties(profile));
             KafkaProducer<byte[],byte[]> producer = new KafkaProducer<>(producerProperties(profile,request))) {
            Sample baseline = sample(admin, initial.topic());
            Integer baselineController = controller(profile);
            Map<Integer,Integer> previousLeaders = baseline.leaders();
            Integer previousController = baselineController;
            long controllerChanges=0, leaderChanges=0, maxUrp=baseline.underReplicated(), maxOffline=baseline.offline();
            Instant failureAt=null, recoveredAt=null; int healthyStreak=0; double minRate=Double.MAX_VALUE; long previousSent=0; long previousTick=System.nanoTime();
            long deadline = System.nanoTime()+Duration.ofSeconds(warmup+observe).toNanos();
            long warmupEnd = System.nanoTime()+Duration.ofSeconds(warmup).toNanos();
            byte[] payload=new byte[messageSize];
            while(System.nanoTime()<deadline){
                long batch=Math.max(1, request.targetMessagesPerSec()<=0?1000:Math.round(request.targetMessagesPerSec()/5d));
                for(long i=0;i<batch;i++) producer.send(new ProducerRecord<>(initial.topic(),payload),(m,e)->{if(e==null){sent.incrementAndGet();bytes.addAndGet(messageSize);}else errors.incrementAndGet();});
                producer.flush();
                Sample s=sample(admin,initial.topic()); Integer c=controller(profile);
                if(previousController!=null&&c!=null&&!previousController.equals(c)) controllerChanges++;
                leaderChanges += leaderChanges(previousLeaders,s.leaders()); previousLeaders=s.leaders(); previousController=c;
                maxUrp=Math.max(maxUrp,s.underReplicated()); maxOffline=Math.max(maxOffline,s.offline());
                long now=System.nanoTime(); double interval=Math.max(.001,(now-previousTick)/1_000_000_000d); double rate=(sent.get()-previousSent)/interval;
                previousSent=sent.get(); previousTick=now; minRate=Math.min(minRate,rate);
                boolean degraded=s.underReplicated()>0||s.offline()>0||c==null||errors.get()>0||controllerChanges>0||leaderChanges>0;
                if(now>=warmupEnd&&failureAt==null&&degraded) failureAt=Instant.now();
                boolean healthy=s.underReplicated()==0&&s.offline()==0&&c!=null&&rate>0;
                if(failureAt!=null){healthyStreak=healthy?healthyStreak+1:0;if(healthyStreak>=3&&recoveredAt==null) recoveredAt=Instant.now();}
                double avg=sent.get()/Math.max(.001,(now-startNanos)/1_000_000_000d);
                runtime.put(initial.id(),initial.progress(baselineController,c,controllerChanges,leaderChanges,failureAt,recoveredAt,sent.get(),bytes.get(),errors.get(),avg,minRate==Double.MAX_VALUE?0:minRate,maxUrp,maxOffline));
                if(request.targetMessagesPerSec()>0) sleepForRate(batch,request.targetMessagesPerSec()); else Thread.sleep(200);
            }
            double avg=sent.get()/Math.max(.001,(System.nanoTime()-startNanos)/1_000_000_000d);
            String consistency=errors.get()==0&&maxOffline==0?"PASS":"FAIL";
            String status=failureAt==null?"COMPLETED_NO_FAILURE":recoveredAt==null?"COMPLETED_NOT_RECOVERED":"COMPLETED";
            FailoverRun done=initial.complete(status,baselineController,previousController,controllerChanges,leaderChanges,failureAt,recoveredAt,sent.get(),bytes.get(),errors.get(),avg,minRate==Double.MAX_VALUE?0:minRate,maxUrp,maxOffline,consistency);
            runtime.put(initial.id(),done); persist(done);
        } catch(Exception e){FailoverRun failed=initial.failed(rootMessage(e));runtime.put(initial.id(),failed);persist(failed);}
    }

    private Sample sample(AdminClient admin,String topic) throws Exception {
        TopicDescription d=admin.describeTopics(List.of(topic)).allTopicNames().get(ADMIN_TIMEOUT_SECONDS,TimeUnit.SECONDS).get(topic);
        if(d==null) throw new IllegalStateException("Topic not found: "+topic);
        Map<Integer,Integer> leaders=new HashMap<>(); long urp=0,offline=0;
        for(TopicPartitionInfo p:d.partitions()){leaders.put(p.partition(),p.leader()==null?null:p.leader().id());if(p.isr().size()<p.replicas().size())urp++;if(p.leader()==null)offline++;}
        return new Sample(leaders,urp,offline);
    }
    private Integer controller(KafkaProfileService.Profile p){try{return connections.check(p.id()).controllerId();}catch(Exception e){return null;}}
    private static long leaderChanges(Map<Integer,Integer>a,Map<Integer,Integer>b){return b.entrySet().stream().filter(e->a.containsKey(e.getKey())&&!java.util.Objects.equals(a.get(e.getKey()),e.getValue())).count();}
    private Properties producerProperties(KafkaProfileService.Profile p,FailoverRequest r){Properties x=connections.clientProperties(p);x.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class.getName());x.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class.getName());x.put(ProducerConfig.ACKS_CONFIG,r.acks()==null||r.acks().isBlank()?"all":r.acks());x.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,"true");x.put(ProducerConfig.CLIENT_ID_CONFIG,p.clientIdPrefix()+"-m4-failover");return x;}
    private static void sleepForRate(long batch,double rate)throws InterruptedException{long ms=Math.max(1,Math.round(batch/rate*1000d));Thread.sleep(Math.min(ms,1000));}
    private void persist(FailoverRun r){jdbc.update("""
      UPDATE kafka_m4_failover_run SET status=?,finished_at=?,duration_ms=?,baseline_controller_id=?,final_controller_id=?,controller_changes=?,leader_changes=?,failure_detected_at=?,recovered_at=?,recovery_time_ms=?,sent_messages=?,sent_bytes=?,producer_errors=?,avg_messages_sec=?,min_messages_sec=?,max_under_replicated=?,max_offline_partitions=?,consistency_status=?,error_message=? WHERE id=?
      """,r.status(),toOffsetDateTime(r.finishedAt()),r.finishedAt()==null?null:Duration.between(r.startedAt(),r.finishedAt()).toMillis(),r.baselineControllerId(),r.finalControllerId(),r.controllerChanges(),r.leaderChanges(),toOffsetDateTime(r.failureDetectedAt()),toOffsetDateTime(r.recoveredAt()),r.recoveryTimeMs(),r.sentMessages(),r.sentBytes(),r.producerErrors(),r.avgMessagesPerSec(),r.minMessagesPerSec(),r.maxUnderReplicated(),r.maxOfflinePartitions(),r.consistencyStatus(),r.errorMessage(),r.id());}
    private static OffsetDateTime toOffsetDateTime(Instant value){return value==null?null:OffsetDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static String rootMessage(Throwable e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
    private record Sample(Map<Integer,Integer> leaders,long underReplicated,long offline){}

    public record FailoverRequest(UUID profileId,String topic,int warmupSeconds,int observationSeconds,int messageSizeBytes,double targetMessagesPerSec,String acks){}
    public record FailoverRun(UUID id,UUID profileId,String topic,String status,Instant startedAt,Instant finishedAt,int warmupSeconds,int observationSeconds,Integer baselineControllerId,Integer finalControllerId,long controllerChanges,long leaderChanges,Instant failureDetectedAt,Instant recoveredAt,Long recoveryTimeMs,long sentMessages,long sentBytes,long producerErrors,double avgMessagesPerSec,double minMessagesPerSec,long maxUnderReplicated,long maxOfflinePartitions,String consistencyStatus,String errorMessage){
        static FailoverRun running(UUID id,UUID p,String t,Instant s,int w,int o){return new FailoverRun(id,p,t,"RUNNING",s,null,w,o,null,null,0,0,null,null,null,0,0,0,0,0,0,0,"PENDING",null);}
        FailoverRun progress(Integer bc,Integer fc,long cc,long lc,Instant f,Instant r,long sent,long bytes,long errors,double avg,double min,long urp,long off){return new FailoverRun(id,profileId,topic,"RUNNING",startedAt,null,warmupSeconds,observationSeconds,bc,fc,cc,lc,f,r,recovery(f,r),sent,bytes,errors,avg,min,urp,off,"PENDING",null);}
        FailoverRun complete(String st,Integer bc,Integer fc,long cc,long lc,Instant f,Instant r,long sent,long bytes,long errors,double avg,double min,long urp,long off,String consistency){return new FailoverRun(id,profileId,topic,st,startedAt,Instant.now(),warmupSeconds,observationSeconds,bc,fc,cc,lc,f,r,recovery(f,r),sent,bytes,errors,avg,min,urp,off,consistency,null);}
        FailoverRun failed(String e){return new FailoverRun(id,profileId,topic,"FAILED",startedAt,Instant.now(),warmupSeconds,observationSeconds,baselineControllerId,finalControllerId,controllerChanges,leaderChanges,failureDetectedAt,recoveredAt,recoveryTimeMs,sentMessages,sentBytes,producerErrors,avgMessagesPerSec,minMessagesPerSec,maxUnderReplicated,maxOfflinePartitions,"FAIL",e);}
        private static Long recovery(Instant f,Instant r){return f==null||r==null?null:Duration.between(f,r).toMillis();}
    }
}
