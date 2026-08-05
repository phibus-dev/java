package dev.phibus.s3.web;

import dev.phibus.s3.workload.WorkloadProfileCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workloads")
public class WorkloadController {
    private final WorkloadProfileCatalog catalog;

    public WorkloadController(WorkloadProfileCatalog catalog) { this.catalog = catalog; }

    @GetMapping
    public List<WorkloadProfileCatalog.WorkloadProfile> list() { return catalog.list(); }
}
