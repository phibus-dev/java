package dev.phibus.s3.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {
    @Test
    void mapsSupportedClientRolesAndIgnoresRealmAndUnknownRoles() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), Map.of(
                "sub", "user-1",
                "realm_access", Map.of("roles", List.of("ADMIN")),
                "resource_access", Map.of(
                        "s3-perf", Map.of("roles", List.of("admin", "viewer", "unrelated")),
                        "other-client", Map.of("roles", List.of("operator")))));

        var converter = new SecurityConfiguration.KeycloakRoleConverter(
                "s3-perf", "ADMIN", "OPERATOR", "VIEWER");
        var authorities = converter.convert(jwt);

        assertThat(authorities).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_VIEWER");
    }

    @Test
    void supportsCustomClientRoleNames() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), Map.of("sub", "user-1",
                "resource_access", Map.of("s3-perf", Map.of("roles", List.of("evo-admin", "evo-operator")))));

        var converter = new SecurityConfiguration.KeycloakRoleConverter(
                "s3-perf", "EVO-ADMIN", "EVO-OPERATOR", "EVO-VIEWER");
        var authorities = converter.convert(jwt);

        assertThat(authorities).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR");
    }
}
