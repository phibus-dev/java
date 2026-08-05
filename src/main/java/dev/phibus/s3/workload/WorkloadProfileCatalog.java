package dev.phibus.s3.workload;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WorkloadProfileCatalog {
    private final List<WorkloadProfile> profiles = List.of(
            new WorkloadProfile("CUSTOM", "Пользовательский", "Распределение операций задаётся вручную", Map.of()),
            new WorkloadProfile("INGESTION", "Ingestion", "Преимущественная загрузка данных", Map.of("UPLOAD", 90, "HEAD", 10)),
            new WorkloadProfile("ANALYTICS", "Analytics", "Чтение данных и проверка метаданных", Map.of("DOWNLOAD", 80, "HEAD", 20)),
            new WorkloadProfile("ETL", "ETL", "Запись, чтение и сканирование объектов", Map.of("UPLOAD", 40, "DOWNLOAD", 40, "LIST", 20)),
            new WorkloadProfile("BACKUP", "Backup", "Запись с последующей проверкой и чтением", Map.of("UPLOAD", 70, "DOWNLOAD", 20, "HEAD", 10)),
            new WorkloadProfile("ARCHIVE", "Archive", "Загрузка, удаление и просмотр пространства имён", Map.of("UPLOAD", 45, "DELETE", 45, "LIST", 10))
    );

    public List<WorkloadProfile> list() { return profiles; }

    public WorkloadProfile get(String id) {
        return profiles.stream().filter(p -> p.id().equalsIgnoreCase(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown workload profile: " + id));
    }

    public record WorkloadProfile(String id, String name, String description, Map<String, Integer> weights) { }
}
