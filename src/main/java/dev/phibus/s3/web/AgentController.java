package dev.phibus.s3.web;

import dev.phibus.s3.distributed.AgentRegistry;
import java.util.List;
import java.util.UUID;
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
@RequestMapping("/api/agents")
public class AgentController {
    private final AgentRegistry registry;

    public AgentController(AgentRegistry registry) { this.registry = registry; }

    @GetMapping
    public List<AgentRegistry.AgentView> list() { return registry.list(); }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRegistry.RegistrationResult register(
            @RequestHeader("X-Agent-Registration-Token") String registrationToken,
            @RequestBody AgentRegistry.RegistrationRequest request) {
        return registry.register(request, registrationToken);
    }

    @PostMapping("/{agentId}/heartbeat")
    public AgentRegistry.AgentView heartbeat(
            @PathVariable UUID agentId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody AgentRegistry.HeartbeatRequest request) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        registry.heartbeat(agentId, token, request);
        return registry.list().stream().filter(agent -> agent.id().equals(agentId)).findFirst().orElseThrow();
    }
}
