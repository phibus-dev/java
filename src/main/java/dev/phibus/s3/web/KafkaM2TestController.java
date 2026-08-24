package dev.phibus.s3.web;

import dev.phibus.s3.kafka.KafkaM2RunService;
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
@RequestMapping("/api/kafka")
public class KafkaM2TestController {
    private final KafkaM2RunService service;
    public KafkaM2TestController(KafkaM2RunService service) { this.service = service; }

    @PostMapping("/consumer-tests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KafkaM2RunService.Snapshot consumer(@RequestBody KafkaM2RunService.ConsumerRequest request, Principal principal) {
        return service.startConsumer(request, principal == null ? null : principal.getName());
    }

    @PostMapping("/e2e-tests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KafkaM2RunService.Snapshot e2e(@RequestBody KafkaM2RunService.E2eRequest request, Principal principal) {
        return service.startE2e(request, principal == null ? null : principal.getName());
    }

    @GetMapping("/m2-tests/{id}")
    public KafkaM2RunService.Snapshot get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/m2-tests")
    public List<KafkaM2RunService.Snapshot> history(@RequestParam(defaultValue = "100") int limit) { return service.history(limit); }
}
