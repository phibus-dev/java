package dev.phibus.s3.scenario;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TestScenarioCatalog {
    private static final List<ScenarioDefinition> SCENARIOS = List.of(
            new ScenarioDefinition("CUSTOM", "Пользовательский", "Параметры задаются вручную",
                    "UPLOAD", 1, 1024, 64, 4, true),
            new ScenarioDefinition("SMOKE", "Smoke", "Быстрая проверка полного жизненного цикла объекта",
                    "LIFECYCLE", 1, 16, 8, 1, true),
            new ScenarioDefinition("UPLOAD_PERFORMANCE", "Upload performance",
                    "Измерение производительности параллельной multipart-загрузки",
                    "UPLOAD", 4, 1024, 64, 8, true),
            new ScenarioDefinition("READ_PERFORMANCE", "Read performance",
                    "Измерение скорости последовательного чтения ранее созданных объектов",
                    "DOWNLOAD", 4, 1024, 64, 8, false),
            new ScenarioDefinition("METADATA", "Metadata operations",
                    "Измерение задержки HeadObject для набора объектов",
                    "HEAD", 100, 1, 5, 16, false),
            new ScenarioDefinition("LIST", "List objects",
                    "Измерение задержки получения списка объектов по prefix",
                    "LIST", 1000, 1, 5, 1, false)
    );

    public List<ScenarioDefinition> list() {
        return SCENARIOS;
    }

    public ScenarioDefinition get(String id) {
        return SCENARIOS.stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + id));
    }

    public record ScenarioDefinition(String id, String name, String description, String operation,
                                     int objectCount, long objectSizeMiB, long partSizeMiB,
                                     int parallelism, boolean deleteAfterTest) { }
}
