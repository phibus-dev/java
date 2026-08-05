package dev.phibus.s3.security;

import dev.phibus.s3.security.SecurityAuditRepository.SecurityAuditEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "s3perf.security.audit-enabled", havingValue = "true", matchIfMissing = true)
public class SecurityAuditFilter extends OncePerRequestFilter {
    private static final Logger AUDIT = LoggerFactory.getLogger("SECURITY_AUDIT");
    private final SecurityAuditRepository repository;

    public SecurityAuditFilter(SecurityAuditRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant started = Instant.now();
        try {
            chain.doFilter(request, response);
        } finally {
            if (isAudited(request)) {
                Principal principal = request.getUserPrincipal();
                String username = principal == null ? "anonymous" : principal.getName();
                long durationMs = Duration.between(started, Instant.now()).toMillis();
                AUDIT.info("user={} method={} path={} status={} durationMs={} remoteAddress={}", username,
                        request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs, request.getRemoteAddr());
                try {
                    repository.save(new SecurityAuditEvent(username, action(request), request.getMethod(),
                            request.getRequestURI(), response.getStatus(), request.getRemoteAddr(), durationMs));
                } catch (RuntimeException exception) {
                    AUDIT.warn("Unable to persist security audit event: {}", exception.getMessage());
                }
            }
        }
    }

    private String action(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/settings") || path.startsWith("/settings")) {
            return "SETTINGS_ACCESS";
        }
        return request.getMethod() + " " + path;
    }

    private boolean isAudited(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return !("GET".equals(method) || "HEAD".equals(method))
                || path.startsWith("/settings") || path.startsWith("/api/settings");
    }
}
