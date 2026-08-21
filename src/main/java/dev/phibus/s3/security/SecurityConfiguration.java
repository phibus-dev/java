package dev.phibus.s3.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; "
                    + "form-action 'self'; img-src 'self' data:; connect-src 'self'; script-src 'self'; style-src 'self'";

    private static final String[] AGENT_API_CSRF_IGNORED = {
            "/api/agents/register",
            "/api/agents/*/heartbeat",
            "/api/distributed-tests/agent/**"
    };

    private final KeycloakRoleConverter roleConverter;

    public SecurityConfiguration(
            @Value("${s3perf.security.keycloak.client-id:}") String clientId,
            @Value("${s3perf.security.keycloak.admin-role:ADMIN}") String adminRole,
            @Value("${s3perf.security.keycloak.operator-role:OPERATOR}") String operatorRole,
            @Value("${s3perf.security.keycloak.viewer-role:VIEWER}") String viewerRole) {
        this.roleConverter = new KeycloakRoleConverter(clientId, adminRole, operatorRole, viewerRole);
    }

    @Bean
    @ConditionalOnProperty(name = "s3perf.security.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
        configureHeaders(http);
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(AGENT_API_CSRF_IGNORED))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "s3perf.security.enabled", havingValue = "true")
    SecurityFilterChain keycloakSecurity(HttpSecurity http, JwtDecoder jwtDecoder,
                                         ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        configureHeaders(http);
        OidcUserService delegate = new OidcUserService();
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(AGENT_API_CSRF_IGNORED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/settings", "/api/settings/**", "/static/**", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/agents/register", "/api/agents/*/heartbeat",
                                "/api/distributed-tests/agent/**").permitAll()
                        .requestMatchers("/api/settings/**", "/api/s3-profiles/**", "/api/audit/**", "/audit.html")
                                .hasRole("ADMIN")
                        .requestMatchers("/api/schedules/**", "/api/distributed-tests/**", "/api/tests/**")
                                .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/settings/**").hasRole("ADMIN")
                        .anyRequest().hasAnyRole("ADMIN", "OPERATOR", "VIEWER"))
                .oauth2Login(oauth -> oauth.userInfoEndpoint(userInfo -> userInfo.oidcUserService(userRequest ->
                        loadOidcUserWithClientRoles(delegate, jwtDecoder, userRequest))))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)))
                .build();
    }

    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository repository) {
        OidcClientInitiatedLogoutSuccessHandler handler = new OidcClientInitiatedLogoutSuccessHandler(repository);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }

    private OidcUser loadOidcUserWithClientRoles(OidcUserService delegate, JwtDecoder jwtDecoder,
                                                  OidcUserRequest userRequest) {
        OidcUser user = delegate.loadUser(userRequest);
        Jwt accessToken = jwtDecoder.decode(userRequest.getAccessToken().getTokenValue());
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());
        authorities.addAll(roleConverter.convert(accessToken));
        return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo());
    }

    private static void configureHeaders(HttpSecurity http) throws Exception {
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(), geolocation=(), payment=()"))
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .preload(true)
                        .maxAgeInSeconds(31536000)));
    }

    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roleConverter);
        return converter;
    }

    static final class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        private final String clientId;
        private final String adminRole;
        private final String operatorRole;
        private final String viewerRole;

        KeycloakRoleConverter(String clientId, String adminRole, String operatorRole, String viewerRole) {
            this.clientId = trim(clientId);
            this.adminRole = normalizeRole(adminRole);
            this.operatorRole = normalizeRole(operatorRole);
            this.viewerRole = normalizeRole(viewerRole);
        }

        @Override
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Set<GrantedAuthority> authorities = new LinkedHashSet<>();
            Object resourceAccessValue = jwt.getClaims().get("resource_access");
            if (!(resourceAccessValue instanceof Map<?, ?> resourceAccess)) {
                return authorities;
            }
            Object clientAccessValue = resourceAccess.get(clientId);
            if (!(clientAccessValue instanceof Map<?, ?> clientAccess)) {
                return authorities;
            }
            Object rolesValue = clientAccess.get("roles");
            if (!(rolesValue instanceof Collection<?> roles)) {
                return authorities;
            }
            for (Object roleValue : roles) {
                String role = normalizeRole(String.valueOf(roleValue));
                if (role.equals(adminRole)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                } else if (role.equals(operatorRole)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
                } else if (role.equals(viewerRole)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_VIEWER"));
                }
            }
            return authorities;
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }

        private static String normalizeRole(String value) {
            return trim(value).toUpperCase();
        }
    }
}
