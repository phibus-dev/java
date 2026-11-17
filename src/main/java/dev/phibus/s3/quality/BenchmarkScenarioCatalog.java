package dev.phibus.s3.quality;

import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.test.TestType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BenchmarkScenarioCatalog {
    private static final List<RunScope> ALL_SCOPES = List.of(RunScope.LOCAL, RunScope.DISTRIBUTED);
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("S3_UPLOAD_THROUGHPUT", TestType.S3, "S3 upload throughput",
                    "Загрузка четырёх объектов по 1 GiB с multipart и параллелизмом 16.",
                    ALL_SCOPES, 0, 0, "BASELINE_TOLERANT",
                    Map.of("operation", "UPLOAD", "executionMode", "OBJECT_COUNT",
                            "objectCount", 4, "objectSizeMiB", 1024, "partSizeMiB", 32,
                            "parallelism", 16, "deleteAfterTest", true)),
            new Scenario("S3_MIXED_STEADY", TestType.S3, "S3 mixed steady load",
                    "Смешанная нагрузка в течение 10 минут после минутного прогрева.",
                    ALL_SCOPES, 600, 60, "BASELINE_TOLERANT",
                    Map.of("operation", "MIXED", "executionMode", "TIME_DURATION",
                            "durationSeconds", 600, "warmupSeconds", 60, "objectSizeMiB", 32,
                            "partSizeMiB", 8, "parallelism", 16, "deleteAfterTest", true,
                            "workloadProfile", "ETL")),
            new Scenario("CLICKHOUSE_INSERT_THROUGHPUT", TestType.CLICKHOUSE,
                    "ClickHouse insert throughput", "Вставка миллиона строк пакетами по 10 000.",
                    ALL_SCOPES, 0, 0, "BASELINE_TOLERANT",
                    Map.of("operation", "INSERT", "rowCount", 1_000_000, "concurrency", 8,
                            "batchSize", 10_000, "payloadBytes", 256, "autoCreateTable", true)),
            new Scenario("CLICKHOUSE_SELECT_LATENCY", TestType.CLICKHOUSE,
                    "ClickHouse select latency", "10 000 SELECT-запросов с параллелизмом 16.",
                    ALL_SCOPES, 0, 0, "BASELINE_STRICT",
                    Map.of("operation", "SELECT", "queryCount", 10_000, "concurrency", 16,
                            "payloadBytes", 128, "autoCreateTable", false)),
            new Scenario("KAFKA_PRODUCER_THROUGHPUT", TestType.KAFKA,
                    "Kafka producer throughput", "Миллион сообщений по 1 KiB в восемь потоков.",
                    ALL_SCOPES, 0, 0, "BASELINE_TOLERANT",
                    Map.of("messageCount", 1_000_000, "messageSizeBytes", 1024,
                            "producerThreads", 8, "acks", "all", "compressionType", "none",
                            "batchSize", 65_536, "lingerMs", 5, "enableIdempotence", true,
                            "targetMessagesPerSec", 0, "maxInFlightMessages", 20_000)),
            new Scenario("KAFKA_PRODUCER_LATENCY", TestType.KAFKA,
                    "Kafka producer latency", "100 000 сообщений одним потоком с ограничением 1000 msg/s.",
                    ALL_SCOPES, 0, 0, "BASELINE_STRICT",
                    Map.of("messageCount", 100_000, "messageSizeBytes", 1024,
                            "producerThreads", 1, "acks", "all", "compressionType", "none",
                            "batchSize", 16_384, "lingerMs", 0, "enableIdempotence", true,
                            "targetMessagesPerSec", 1_000, "maxInFlightMessages", 10_000))
    );

    public List<Scenario> list(TestType testType) {
        return testType == null ? SCENARIOS
                : SCENARIOS.stream().filter(item -> item.testType() == testType).toList();
    }

    public Scenario get(String suppliedId) {
        String id = suppliedId == null ? "" : suppliedId.trim().toUpperCase(Locale.ROOT);
        return SCENARIOS.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown benchmark scenario: " + suppliedId));
    }

    public record Scenario(String id, TestType testType, String name, String description,
                           List<RunScope> supportedScopes, long measurementSeconds,
                           long warmupSeconds, String recommendedPolicyId,
                           Map<String, Object> parameters) { }
}
