package dev.phibus.s3.test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LoadTestEngineRegistry {
    private final Map<TestType, LoadTestEngine> engines;

    public LoadTestEngineRegistry(List<LoadTestEngine> discoveredEngines) {
        Map<TestType, LoadTestEngine> registered = new EnumMap<>(TestType.class);
        for (LoadTestEngine engine : discoveredEngines) {
            LoadTestEngine previous = registered.put(engine.type(), engine);
            if (previous != null) {
                throw new IllegalStateException("Multiple load test engines registered for " + engine.type());
            }
        }
        this.engines = Map.copyOf(registered);
    }

    public LoadTestEngine require(TestType type) {
        LoadTestEngine engine = engines.get(type);
        if (engine == null) {
            throw new IllegalArgumentException("Load test engine is not available: " + type);
        }
        return engine;
    }

    public boolean supports(TestType type) {
        return engines.containsKey(type);
    }

    public List<TestType> supportedTypes() {
        return engines.keySet().stream().sorted().toList();
    }
}
