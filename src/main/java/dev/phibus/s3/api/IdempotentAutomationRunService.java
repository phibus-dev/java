package dev.phibus.s3.api;

import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.clickhouse.ClickHouseTestRunService;
import dev.phibus.s3.distributed.DistributedTestService;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.kafka.KafkaProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class IdempotentAutomationRunService {
    private final ApiIdempotencyCoordinator coordinator;
    private final ClickHouseProfileService clickHouseProfiles;
    private final KafkaProfileService kafkaProfiles;
    private final ClickHouseTestRunService clickHouseRuns;
    private final KafkaProducerRunService kafkaRuns;
    private final DistributedTestService distributedRuns;

    public IdempotentAutomationRunService(ApiIdempotencyCoordinator coordinator,
                                          ClickHouseProfileService clickHouseProfiles,
                                          KafkaProfileService kafkaProfiles,
                                          ClickHouseTestRunService clickHouseRuns,
                                          KafkaProducerRunService kafkaRuns,
                                          DistributedTestService distributedRuns) {
        this.coordinator = coordinator;
        this.clickHouseProfiles = clickHouseProfiles;
        this.kafkaProfiles = kafkaProfiles;
        this.clickHouseRuns = clickHouseRuns;
        this.kafkaRuns = kafkaRuns;
        this.distributedRuns = distributedRuns;
    }

    public ApiIdempotencyCoordinator.StartResult startClickHouse(
            String key, ClickHouseTestRequest request, String clientId) {
        return coordinator.start(key, request, clientId, "CLICKHOUSE_RUN", runId -> {
            requireClickHouseProfile(request.profileId());
            return clickHouseRuns.create(runId, request).id();
        });
    }

    public ApiIdempotencyCoordinator.StartResult startKafka(
            String key, KafkaProducerRunService.ProducerRequest request, String clientId) {
        return coordinator.start(key, request, clientId, "KAFKA_RUN", runId -> {
            requireKafkaProfile(request.profileId());
            return kafkaRuns.start(runId, request, clientId).id();
        });
    }

    public ApiIdempotencyCoordinator.StartResult startDistributedS3(
            String key, DistributedS3RunRequest supplied, String clientId) {
        DistributedS3RunRequest request = supplied == null ? null : supplied.normalized();
        return coordinator.start(key, request, clientId, "DISTRIBUTED_S3_RUN", runId ->
                distributedRuns.create(runId, new DistributedTestService.CreateDistributedTestRequest(
                        request.name(), request.agentIds(), request.testRequest().toTestRequest())).id());
    }

    public ApiIdempotencyCoordinator.StartResult startDistributedClickHouse(
            String key, DistributedClickHouseRunRequest supplied, String clientId) {
        DistributedClickHouseRunRequest request = supplied == null ? null : supplied.normalized();
        return coordinator.start(key, request, clientId, "DISTRIBUTED_CLICKHOUSE_RUN", runId -> {
            requireClickHouseProfile(request.testRequest().profileId());
            return distributedRuns.createClickHouse(runId,
                    new DistributedTestService.CreateDistributedClickHouseTestRequest(
                            request.name(), request.agentIds(), request.testRequest(),
                            request.endpointByAgent())).id();
        });
    }

    public ApiIdempotencyCoordinator.StartResult startDistributedKafka(
            String key, DistributedKafkaRunRequest supplied, String clientId) {
        DistributedKafkaRunRequest request = supplied == null ? null : supplied.normalized();
        return coordinator.start(key, request, clientId, "DISTRIBUTED_KAFKA_RUN", runId -> {
            requireKafkaProfile(request.testRequest().profileId());
            return distributedRuns.createKafka(runId,
                    new DistributedTestService.CreateDistributedKafkaTestRequest(
                            request.name(), request.agentIds(), request.testRequest())).id();
        });
    }

    private void requireClickHouseProfile(UUID profileId) {
        if (profileId == null) throw new IllegalArgumentException("ClickHouse profile is required");
        try {
            clickHouseProfiles.get(profileId);
        } catch (IllegalArgumentException error) {
            throw new IdempotentS3RunService.ProfileNotFoundException("ClickHouse", profileId);
        }
    }

    private void requireKafkaProfile(UUID profileId) {
        if (profileId == null) throw new IllegalArgumentException("Kafka profile is required");
        try {
            kafkaProfiles.get(profileId);
        } catch (IllegalArgumentException error) {
            throw new IdempotentS3RunService.ProfileNotFoundException("Kafka", profileId);
        }
    }

    public record DistributedS3RunRequest(
            @NotBlank String name,
            @NotEmpty @Size(max = 256) List<@NotNull UUID> agentIds,
            @NotNull @Valid S3RunRequest testRequest) {
        DistributedS3RunRequest normalized() {
            return new DistributedS3RunRequest(name == null ? null : name.trim(),
                    agentIds == null ? null : List.copyOf(agentIds),
                    testRequest == null ? null : testRequest.normalized());
        }
    }

    public record DistributedClickHouseRunRequest(
            @NotBlank String name,
            @NotEmpty @Size(max = 256) List<@NotNull UUID> agentIds,
            @NotNull @Valid ClickHouseTestRequest testRequest,
            Map<UUID, String> endpointByAgent) {
        DistributedClickHouseRunRequest normalized() {
            return new DistributedClickHouseRunRequest(name == null ? null : name.trim(),
                    agentIds == null ? null : List.copyOf(agentIds), testRequest,
                    endpointByAgent == null ? Map.of() : Map.copyOf(endpointByAgent));
        }
    }

    public record DistributedKafkaRunRequest(
            @NotBlank String name,
            @NotEmpty @Size(max = 256) List<@NotNull UUID> agentIds,
            @NotNull @Valid KafkaProducerRunService.ProducerRequest testRequest) {
        DistributedKafkaRunRequest normalized() {
            return new DistributedKafkaRunRequest(name == null ? null : name.trim(),
                    agentIds == null ? null : List.copyOf(agentIds), testRequest);
        }
    }
}
