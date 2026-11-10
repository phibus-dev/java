package dev.phibus.s3.distributed;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.phibus.s3.clickhouse.ClickHouseConnectionSpec;
import dev.phibus.s3.clickhouse.ClickHouseHistoryStore;
import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.kafka.KafkaProfileService;
import dev.phibus.s3.test.LatencyHistogramCodec;
import dev.phibus.s3.test.S3LatencyScope;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.HdrHistogram.Histogram;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class DistributedTestService {
    private final AgentRegistry registry;
    private final ClickHouseProfileService clickHouseProfiles;
    private final ClickHouseHistoryStore clickHouseHistory;
    private final TestHistoryStore testHistory;
    private final KafkaProfileService kafkaProfiles;
    private final Map<UUID, DistributedRun> runs = new ConcurrentHashMap<>();
    private final Map<UUID, Assignment> assignments = new ConcurrentHashMap<>();

    public DistributedTestService(AgentRegistry registry, ClickHouseProfileService clickHouseProfiles,
                                  ClickHouseHistoryStore clickHouseHistory, TestHistoryStore testHistory,
                                  KafkaProfileService kafkaProfiles) {
        this.registry = registry;
        this.clickHouseProfiles = clickHouseProfiles;
        this.clickHouseHistory = clickHouseHistory;
        this.testHistory = testHistory;
        this.kafkaProfiles = kafkaProfiles;
    }

    public DistributedRunView create(CreateDistributedTestRequest request) {
        requireAgents(request.agentIds());
        if (request.testRequest() == null) throw new IllegalArgumentException("S3 test request is required");
        if (notBlank(request.testRequest().accessKey()) || notBlank(request.testRequest().secretKey())) {
            throw new IllegalArgumentException(
                    "Distributed S3 tests require credentials configured locally on each agent");
        }
        UUID runId = UUID.randomUUID(); Instant now = Instant.now();
        Map<UUID, AgentProgress> agents = prepareAgents(request.agentIds(), TestType.S3);
        for (UUID agentId : request.agentIds()) assignments.put(agentId,
                new Assignment(runId, agentId, TestType.S3, request.testRequest(), null, null, null, null, now));
        DistributedRun run = new DistributedRun(runId, request.name(), TestType.S3, request.testRequest(), null, null,
                now, null, "RUNNING", new ConcurrentHashMap<>(agents), null);
        runs.put(runId, run); return view(run);
    }

    public DistributedRunView createClickHouse(CreateDistributedClickHouseTestRequest request) {
        requireAgents(request.agentIds());
        if (request.testRequest() == null) throw new IllegalArgumentException("ClickHouse test request is required");
        UUID runId=UUID.randomUUID(); Instant now=Instant.now();
        Map<UUID,ClickHouseConnectionSpec> connections=new LinkedHashMap<>(); String firstEndpoint=null;
        for(UUID agentId:request.agentIds()){
            String endpoint=request.endpointByAgent()==null?request.testRequest().endpoint():request.endpointByAgent().getOrDefault(agentId,request.testRequest().endpoint());
            ClickHouseConnectionSpec spec=clickHouseProfiles.connectionSpec(request.testRequest().profileId(),endpoint);
            if(firstEndpoint==null) firstEndpoint=spec.endpoint();
            connections.put(agentId,spec);
        }
        Map<UUID,AgentProgress> agents=prepareAgents(request.agentIds(),TestType.CLICKHOUSE);
        for(UUID agentId:request.agentIds()){
            ClickHouseConnectionSpec spec=connections.get(agentId);
            ClickHouseTestRequest agentRequest=new ClickHouseTestRequest(request.testRequest().profileId(),spec.endpoint(),request.testRequest().table(),request.testRequest().operation(),request.testRequest().concurrency(),request.testRequest().batchSize(),request.testRequest().rowCount(),request.testRequest().durationSeconds(),request.testRequest().warmupSeconds(),request.testRequest().payloadBytes(),request.testRequest().queryCount(),request.testRequest().autoCreateTable());
            assignments.put(agentId,new Assignment(runId,agentId,TestType.CLICKHOUSE,null,agentRequest,spec,null,null,now));
        }
        DistributedRun run=new DistributedRun(runId,request.name(),TestType.CLICKHOUSE,null,request.testRequest(),null,now,null,"RUNNING",new ConcurrentHashMap<>(agents),firstEndpoint);
        runs.put(runId,run); return view(run);
    }

    public DistributedRunView createKafka(CreateDistributedKafkaTestRequest request) {
        requireAgents(request.agentIds());
        if (request.testRequest() == null) throw new IllegalArgumentException("Kafka producer request is required");
        KafkaProfileService.Profile profile = request.testRequest().profileId() == null
                ? kafkaProfiles.defaultProfile() : kafkaProfiles.get(request.testRequest().profileId());
        if (profile == null) throw new IllegalArgumentException("Kafka profile is required");
        UUID runId=UUID.randomUUID(); Instant now=Instant.now();
        Map<UUID,AgentProgress> agents=prepareAgents(request.agentIds(),TestType.KAFKA);
        for(UUID agentId:request.agentIds()) assignments.put(agentId,
                new Assignment(runId,agentId,TestType.KAFKA,null,null,null,request.testRequest(),profile,now));
        DistributedRun run=new DistributedRun(runId,request.name(),TestType.KAFKA,null,null,request.testRequest(),now,null,"RUNNING",new ConcurrentHashMap<>(agents),profile.bootstrapServers());
        runs.put(runId,run); return view(run);
    }

    private Map<UUID,AgentProgress> prepareAgents(List<UUID> agentIds,TestType type){
        Map<UUID,AgentProgress> agents=new LinkedHashMap<>(); List<UUID> reserved=new ArrayList<>(); Instant now=Instant.now();
        String capability=type==TestType.CLICKHOUSE?"CLICKHOUSE":type==TestType.KAFKA?"KAFKA":"S3";
        try {
            for(UUID agentId:agentIds){
                registry.reserve(agentId,capability); reserved.add(agentId);
                agents.put(agentId,new AgentProgress(agentId,"ASSIGNED",0,0,0,0,0,0,0,0,0,null,now,null));
            }
            return agents;
        } catch(RuntimeException error) {
            reserved.forEach(registry::release);
            throw error;
        }
    }

    public Assignment poll(UUID agentId,String token){registry.authenticate(agentId,token);return assignments.get(agentId);}

    public DistributedRunView report(UUID agentId,String token,AgentStatistics stats){
        AgentRegistry.AgentRecord agent=registry.authenticate(agentId,token); Assignment assignment=assignments.get(agentId);
        if(assignment==null||!assignment.runId().equals(stats.runId())) throw new IllegalArgumentException("No matching assignment");
        DistributedRun run=require(stats.runId());
        if(stats.latencyHistogram()!=null) LatencyHistogramCodec.decode(stats.latencyHistogram());
        AgentProgress progress=new AgentProgress(agentId,stats.status(),stats.completedOperations(),stats.rowsProcessed(),stats.bytesTransferred(),stats.operationsPerSecond(),stats.throughputMiBps(),stats.p50LatencyMs(),stats.p95LatencyMs(),stats.p99LatencyMs(),stats.errors(),stats.message(),Instant.now(),stats.latencyHistogram());
        run.agents().put(agentId,progress);
        if(isTerminal(stats.status())){if(run.testType()==TestType.S3)persistS3Agent(run,agent,stats);assignments.remove(agentId);registry.release(agentId);}
        if(run.agents().values().stream().allMatch(p->isTerminal(p.status()))&&!isTerminal(run.status())){
            String status=run.agents().values().stream().anyMatch(p->"FAILED".equals(p.status()))?"FAILED":run.agents().values().stream().anyMatch(p->"CANCELLED".equals(p.status()))?"CANCELLED":"COMPLETED";
            DistributedRun completed=new DistributedRun(run.id(),run.name(),run.testType(),run.s3Request(),run.clickHouseRequest(),run.kafkaRequest(),run.startedAt(),Instant.now(),status,run.agents(),run.primaryEndpoint());
            runs.put(run.id(),completed); if(run.testType()==TestType.CLICKHOUSE)persistClickHouse(completed);
        } return get(run.id());
    }

    private void persistS3Agent(DistributedRun run,AgentRegistry.AgentRecord agent,AgentStatistics stats){
        UUID historyId=UUID.nameUUIDFromBytes((run.id()+":"+agent.id()).getBytes(StandardCharsets.UTF_8));
        testHistory.saveDistributed(new TestHistoryStore.DistributedHistoryResult(historyId,run.s3Request(),agent.name(),stats.status(),run.startedAt(),Instant.now(),stats.completedOperations(),stats.bytesTransferred(),stats.throughputMiBps(),stats.p50LatencyMs(),stats.p95LatencyMs(),stats.p99LatencyMs(),stats.errors(),stats.message()));
    }
    private void persistClickHouse(DistributedRun run){DistributedRunView a=view(run);clickHouseHistory.saveDistributed(run.id(),run.clickHouseRequest(),run.primaryEndpoint(),run.status(),run.startedAt(),run.finishedAt(),a.rowsProcessed(),a.bytesTransferred(),a.completedOperations(),a.errors(),a.rowsPerSecond(),a.throughputMiBps(),a.operationsPerSecond(),a.p50LatencyMs(),a.p95LatencyMs(),a.p99LatencyMs(),"Distributed ClickHouse test: "+run.agents().size()+" agents");}

    public List<DistributedRunView> list(){return runs.values().stream().sorted(Comparator.comparing(DistributedRun::startedAt).reversed()).map(this::view).toList();}
    public DistributedRunView get(UUID id){return view(require(id));}
    private DistributedRun require(UUID id){DistributedRun run=runs.get(id);if(run==null)throw new IllegalArgumentException("Distributed test not found");return run;}

    private DistributedRunView view(DistributedRun run){
        List<AgentProgress> agents=new ArrayList<>(run.agents().values()); long operations=agents.stream().mapToLong(AgentProgress::completedOperations).sum(); long rows=agents.stream().mapToLong(AgentProgress::rowsProcessed).sum(); long bytes=agents.stream().mapToLong(AgentProgress::bytesTransferred).sum(); double ops=agents.stream().mapToDouble(AgentProgress::operationsPerSecond).sum(); double throughput=agents.stream().mapToDouble(AgentProgress::throughputMiBps).sum(); long errors=agents.stream().mapToLong(AgentProgress::errors).sum(); LatencyAggregate latency=aggregateLatency(agents); double rowsPerSecond=run.testType()==TestType.CLICKHOUSE?agents.stream().mapToDouble(a->a.operationsPerSecond()>0&&a.completedOperations()>0?a.rowsProcessed()*a.operationsPerSecond()/a.completedOperations():0).sum():0;
        return new DistributedRunView(run.id(),run.name(),run.testType(),run.status(),run.startedAt(),run.finishedAt(),safeRequest(run.s3Request()),run.clickHouseRequest(),run.kafkaRequest(),operations,rows,bytes,ops,rowsPerSecond,throughput,latency.p50(),latency.p95(),latency.p99(),errors,agents.stream().sorted(Comparator.comparing(AgentProgress::agentId)).toList(),latencyScope(run),latency.method());
    }
    private static LatencyAggregate aggregateLatency(List<AgentProgress> agents){
        List<AgentProgress> measured=agents.stream().filter(a->a.completedOperations()>0).toList();
        if(measured.isEmpty()) return new LatencyAggregate(0,0,0,"NO_SAMPLES");
        if(measured.stream().anyMatch(a->a.latencyHistogram()==null||a.latencyHistogram().isBlank())) return new LatencyAggregate(maxLatency(measured,50),maxLatency(measured,95),maxLatency(measured,99),"LEGACY_MAX");
        Histogram merged=null;
        for(AgentProgress agent:measured){Histogram next=LatencyHistogramCodec.decode(agent.latencyHistogram());if(next==null)continue;if(merged==null)merged=next.copy();else merged.add(next);}
        if(merged==null||merged.getTotalCount()==0) return new LatencyAggregate(0,0,0,"NO_SAMPLES");
        return new LatencyAggregate(merged.getValueAtPercentile(50),merged.getValueAtPercentile(95),merged.getValueAtPercentile(99),"HDR_HISTOGRAM");
    }
    private static double maxLatency(List<AgentProgress> agents,int percentile){return agents.stream().filter(a->a.completedOperations()>0).mapToDouble(a->percentile==50?a.p50LatencyMs():percentile==99?a.p99LatencyMs():a.p95LatencyMs()).max().orElse(0);}
    private static String latencyScope(DistributedRun run){
        if(run.testType()==TestType.CLICKHOUSE)return "CLICKHOUSE_QUERY";
        if(run.testType()==TestType.KAFKA)return "KAFKA_PRODUCE_ACK";
        return S3LatencyScope.forRequest(run.s3Request()).name();
    }
    private static TestRequest safeRequest(TestRequest request){if(request==null)return null;return new TestRequest(request.endpoint(),request.bucket(),request.region(),null,null,request.pathStyleAccess(),request.objectKey(),request.objectSizeMiB(),request.partSizeMiB(),request.parallelism(),request.objectCount(),request.deleteAfterTest(),request.operation(),request.executionMode(),request.durationSeconds(),request.warmupSeconds(),request.workloadProfile(),request.workloadWeights(),request.targetOperationsPerSecond(),request.operationThreads(),request.profileId());}
    private static void requireAgents(List<UUID> agentIds){if(agentIds==null||agentIds.isEmpty())throw new IllegalArgumentException("At least one agent is required");}
    private static boolean notBlank(String value){return value!=null&&!value.isBlank();}
    private static boolean isTerminal(String status){return "COMPLETED".equals(status)||"COMPLETED_WITH_ERRORS".equals(status)||"FAILED".equals(status)||"CANCELLED".equals(status);}

    public record CreateDistributedTestRequest(String name,List<UUID> agentIds,TestRequest testRequest){}
    public record CreateDistributedClickHouseTestRequest(String name,List<UUID> agentIds,ClickHouseTestRequest testRequest,Map<UUID,String> endpointByAgent){}
    public record CreateDistributedKafkaTestRequest(String name,List<UUID> agentIds,KafkaProducerRunService.ProducerRequest testRequest){}
    public record Assignment(UUID runId,UUID agentId,TestType testType,TestRequest testRequest,ClickHouseTestRequest clickHouseRequest,ClickHouseConnectionSpec clickHouseConnection,KafkaProducerRunService.ProducerRequest kafkaRequest,KafkaProfileService.Profile kafkaProfile,Instant assignedAt){}
    public record AgentStatistics(UUID runId,String status,long completedOperations,long rowsProcessed,long bytesTransferred,double operationsPerSecond,double throughputMiBps,double p50LatencyMs,double p95LatencyMs,double p99LatencyMs,long errors,String message,String latencyHistogram){}
    public record AgentProgress(UUID agentId,String status,long completedOperations,long rowsProcessed,long bytesTransferred,double operationsPerSecond,double throughputMiBps,double p50LatencyMs,double p95LatencyMs,double p99LatencyMs,long errors,String message,Instant updatedAt,@JsonIgnore String latencyHistogram){}
    private record LatencyAggregate(double p50,double p95,double p99,String method){}
    private record DistributedRun(UUID id,String name,TestType testType,TestRequest s3Request,ClickHouseTestRequest clickHouseRequest,KafkaProducerRunService.ProducerRequest kafkaRequest,Instant startedAt,Instant finishedAt,String status,Map<UUID,AgentProgress> agents,String primaryEndpoint){}
    public record DistributedRunView(UUID id,String name,TestType testType,String status,Instant startedAt,Instant finishedAt,TestRequest testRequest,ClickHouseTestRequest clickHouseRequest,KafkaProducerRunService.ProducerRequest kafkaRequest,long completedOperations,long rowsProcessed,long bytesTransferred,double operationsPerSecond,double rowsPerSecond,double throughputMiBps,double p50LatencyMs,double p95LatencyMs,double p99LatencyMs,long errors,List<AgentProgress> agents,String latencyScope,String latencyAggregation){}
}
