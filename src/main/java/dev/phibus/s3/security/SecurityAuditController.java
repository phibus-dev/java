package dev.phibus.s3.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class SecurityAuditController {
    private final SecurityAuditRepository repository;

    public SecurityAuditController(SecurityAuditRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<SecurityAuditRepository.SecurityAuditEvent> find(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return repository.find(username, method, status, from, to, limit, offset);
    }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return repository.summary();
    }
}
