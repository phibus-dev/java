package dev.phibus.s3.web;

import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.clickhouse.ClickHouseTestRun;
import dev.phibus.s3.clickhouse.ClickHouseTestRunService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clickhouse/tests")
public class ClickHouseTestController {
    private final ClickHouseTestRunService service;

    public ClickHouseTestController(ClickHouseTestRunService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClickHouseTestRun.Snapshot create(@Valid @RequestBody ClickHouseTestRequest request) {
        return service.create(request).snapshot();
    }

    @GetMapping
    public List<ClickHouseTestRun.Snapshot> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ClickHouseTestRun.Snapshot get(@PathVariable UUID id) {
        return service.get(id).snapshot();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) {
        service.cancel(id);
    }
}
