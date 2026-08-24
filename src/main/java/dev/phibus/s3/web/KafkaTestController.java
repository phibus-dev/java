package dev.phibus.s3.web;

import dev.phibus.s3.kafka.KafkaConnectionService;
import dev.phibus.s3.kafka.KafkaProducerRunService;
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
public class KafkaTestController {
    private final KafkaConnectionService connections;
    private final KafkaProducerRunService producerRuns;

    public KafkaTestController(KafkaConnectionService connections, KafkaProducerRunService producerRuns) {
        this.connections = connections;
        this.producerRuns = producerRuns;
    }

    @PostMapping("/profiles/{id}/check")
    public KafkaConnectionService.ClusterInfo check(@PathVariable UUID id) {
        return connections.check(id);
    }

    @PostMapping("/producer-tests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KafkaProducerRunService.Snapshot start(@RequestBody KafkaProducerRunService.ProducerRequest request,
                                                   Principal principal) {
        return producerRuns.start(request, principal == null ? null : principal.getName());
    }

    @GetMapping("/producer-tests/{id}")
    public KafkaProducerRunService.Snapshot get(@PathVariable UUID id) { return producerRuns.get(id); }

    @GetMapping("/producer-tests")
    public List<KafkaProducerRunService.Snapshot> history(@RequestParam(defaultValue = "100") int limit) {
        return producerRuns.history(limit);
    }
}
