package dev.phibus.s3.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionInfoController {
    private final boolean securityEnabled;
    private final String applicationVersion;
    private final Instant startedAt = Instant.now();

    public SessionInfoController(
            @Value("${s3perf.security.enabled:false}") boolean securityEnabled,
            @Value("${info.app.version:${project.version:dev}}") String applicationVersion) {
        this.securityEnabled = securityEnabled;
        this.applicationVersion = applicationVersion;
    }

    @GetMapping("/api/session")
    public Map<String, Object> session(Authentication authentication) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("securityEnabled", securityEnabled);
        result.put("version", applicationVersion);
        result.put("startedAt", startedAt.toString());
        if (authentication == null || !authentication.isAuthenticated()) {
            result.put("authenticated", false);
            result.put("username", "anonymous");
            result.put("roles", java.util.List.of());
            return result;
        }

        result.put("authenticated", true);
        String username = authentication.getName();
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            String preferred = oidcUser.getPreferredUsername();
            if (preferred != null && !preferred.isBlank()) username = preferred;
        }
        result.put("username", username);
        result.put("roles", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .sorted()
                .collect(Collectors.toList()));
        return result;
    }
}
