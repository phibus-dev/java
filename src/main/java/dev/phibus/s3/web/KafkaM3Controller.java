package dev.phibus.s3.web;

import dev.phibus.s3.kafka.KafkaM3Service;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kafka/m3")
public class KafkaM3Controller {
    private final KafkaM3Service service;

    public KafkaM3Controller(KafkaM3Service service) {
        this.service = service;
    }

    @GetMapping("/kraft-health")
    public KafkaM3Service.KraftHealth kraftHealth(@RequestParam(required = false) UUID profileId) {
        return service.kraftHealth(profileId);
    }

    @PostMapping("/replication-tests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KafkaM3Service.M3Run replication(@RequestBody KafkaM3Service.ReplicationRequest request,
                                            Principal principal) {
        return service.startReplication(request, principal == null ? null : principal.getName());
    }

    @PostMapping("/partition-scaling-tests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KafkaM3Service.M3Run scaling(@RequestBody KafkaM3Service.ScalingRequest request,
                                        Principal principal) {
        return service.startScaling(request, principal == null ? null : principal.getName());
    }

    @GetMapping("/runs/{id}")
    public KafkaM3Service.M3Run get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/runs")
    public List<KafkaM3Service.M3Run> history(@RequestParam(defaultValue = "100") int limit) {
        return service.history(limit);
    }
}
