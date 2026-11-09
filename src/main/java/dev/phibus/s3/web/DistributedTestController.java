package dev.phibus.s3.web;

import dev.phibus.s3.distributed.DistributedTestService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/distributed-tests")
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class DistributedTestController {
    private final DistributedTestService service;
    public DistributedTestController(DistributedTestService service) { this.service = service; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public DistributedTestService.DistributedRunView create(@RequestBody DistributedTestService.CreateDistributedTestRequest request) { return service.create(request); }

    @PostMapping("/clickhouse") @ResponseStatus(HttpStatus.CREATED)
    public DistributedTestService.DistributedRunView createClickHouse(@RequestBody DistributedTestService.CreateDistributedClickHouseTestRequest request) { return service.createClickHouse(request); }

    @PostMapping("/kafka") @ResponseStatus(HttpStatus.CREATED)
    public DistributedTestService.DistributedRunView createKafka(@RequestBody DistributedTestService.CreateDistributedKafkaTestRequest request) { return service.createKafka(request); }

    @GetMapping public List<DistributedTestService.DistributedRunView> list() { return service.list(); }
    @GetMapping("/{id}") public DistributedTestService.DistributedRunView get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/agent/{agentId}/assignment")
    public DistributedTestService.Assignment poll(@PathVariable UUID agentId,@RequestHeader(value="X-Agent-Token",required=false) String agentToken,@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization) { return service.poll(agentId,token(agentToken,authorization)); }

    @PostMapping("/agent/{agentId}/statistics")
    public DistributedTestService.DistributedRunView report(@PathVariable UUID agentId,@RequestHeader(value="X-Agent-Token",required=false) String agentToken,@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,@RequestBody DistributedTestService.AgentStatistics statistics) { return service.report(agentId,token(agentToken,authorization),statistics); }

    private static String bearer(String authorization){return authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):authorization;}
    private static String token(String agentToken,String authorization){return agentToken!=null&&!agentToken.isBlank()?agentToken:bearer(authorization);}
}
