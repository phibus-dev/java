package dev.phibus.s3.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; "
                    + "form-action 'self'; img-src 'self' data:; connect-src 'self'; script-src 'self'; style-src 'self'";

    @Bean
    @ConditionalOnProperty(name = "s3perf.security.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
        configureHeaders(http);
        return http
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "s3perf.security.enabled", havingValue = "true")
    SecurityFilterChain keycloakSecurity(HttpSecurity http) throws Exception {
        configureHeaders(http);
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(
                                "/api/agents/register",
                                "/api/agents/*/heartbeat",
                                "/api/distributed-tests/agent/**"))
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
                .oauth2Login(Customizer.withDefaults())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .build();
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
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        return converter;
    }

    static final class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        @Override
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Set<GrantedAuthority> authorities = new LinkedHashSet<>();
            Object realmAccessValue = jwt.getClaims().get("realm_access");
            if (realmAccessValue instanceof Map<?, ?> realmAccess) {
                Object rolesValue = realmAccess.get("roles");
                if (rolesValue instanceof Collection<?> roles) {
                    roles.stream().map(String::valueOf).map(String::toUpperCase)
                            .filter(role -> role.equals("ADMIN") || role.equals("OPERATOR") || role.equals("VIEWER"))
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .forEach(authorities::add);
                }
            }
            return authorities;
        }
    }
}
