package dev.phibus.s3.web;

import dev.phibus.s3.kafka.KafkaM4FailoverService;
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
@RequestMapping("/api/kafka/m4")
public class KafkaM4Controller {
    private final KafkaM4FailoverService service;
    public KafkaM4Controller(KafkaM4FailoverService service){this.service=service;}

    @PostMapping("/failover-tests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KafkaM4FailoverService.FailoverRun start(@RequestBody KafkaM4FailoverService.FailoverRequest request, Principal principal){
        return service.start(request,principal==null?null:principal.getName());
    }
    @GetMapping("/failover-tests/{id}")
    public KafkaM4FailoverService.FailoverRun get(@PathVariable UUID id){return service.get(id);}
    @GetMapping("/failover-tests")
    public List<KafkaM4FailoverService.FailoverRun> history(@RequestParam(defaultValue="100") int limit){return service.history(limit);}
}
