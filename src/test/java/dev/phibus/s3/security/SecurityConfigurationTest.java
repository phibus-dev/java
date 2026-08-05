package dev.phibus.s3.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {
    @Test
    void mapsSupportedRealmRolesAndIgnoresUnknownRoles() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), Map.of("sub", "user-1",
                "realm_access", Map.of("roles", List.of("admin", "viewer", "unrelated"))));

        var authorities = new SecurityConfiguration.KeycloakRoleConverter().convert(jwt);

        assertThat(authorities).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_VIEWER");
    }
}
