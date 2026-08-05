package dev.phibus.s3.web;

import dev.phibus.s3.scenario.TestScenarioCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {
    private final TestScenarioCatalog catalog;

    public ScenarioController(TestScenarioCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<TestScenarioCatalog.ScenarioDefinition> list() {
        return catalog.list();
    }
}
