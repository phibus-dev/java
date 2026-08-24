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
                    + "form-action 'self' https:; img-src 'self' data:; connect-src 'self'; script-src 'self'; style-src 'self'";
    private static final String[] AGENT_API_CSRF_IGNORED = {"/api/agents/register","/api/agents/*/heartbeat","/api/distributed-tests/agent/**"};
    private final KeycloakRoleConverter roleConverter;

    public SecurityConfiguration(@Value("${s3perf.security.keycloak.client-id:}") String clientId,
            @Value("${s3perf.security.keycloak.admin-role:ADMIN}") String adminRole,
            @Value("${s3perf.security.keycloak.operator-role:OPERATOR}") String operatorRole,
            @Value("${s3perf.security.keycloak.viewer-role:VIEWER}") String viewerRole) {
        this.roleConverter = new KeycloakRoleConverter(clientId, adminRole, operatorRole, viewerRole);
    }

    @Bean
    @ConditionalOnProperty(name="s3perf.security.enabled",havingValue="false",matchIfMissing=true)
    SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
        configureHeaders(http);
        return http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).ignoringRequestMatchers(AGENT_API_CSRF_IGNORED))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    @Bean
    @ConditionalOnProperty(name="s3perf.security.enabled",havingValue="true")
    SecurityFilterChain keycloakSecurity(HttpSecurity http, JwtDecoder jwtDecoder, ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        configureHeaders(http); OidcUserService delegate=new OidcUserService();
        return http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).ignoringRequestMatchers(AGENT_API_CSRF_IGNORED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/settings","/api/settings/**","/static/**","/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAnyRole("ADMIN","OPERATOR")
                        .requestMatchers("/api/agents/register","/api/agents/*/heartbeat","/api/distributed-tests/agent/**").permitAll()
                        .requestMatchers("/api/settings/**","/api/s3-profiles/**","/api/kafka/profiles/**","/api/audit/**","/audit.html").hasRole("ADMIN")
                        .requestMatchers("/api/schedules/**","/api/distributed-tests/**","/api/tests/**","/api/kafka/**").hasAnyRole("ADMIN","OPERATOR")
                        .requestMatchers("/settings/**").hasRole("ADMIN")
                        .anyRequest().hasAnyRole("ADMIN","OPERATOR","VIEWER"))
                .oauth2Login(oauth -> oauth.userInfoEndpoint(ui -> ui.oidcUserService(req -> loadOidcUserWithClientRoles(delegate,jwtDecoder,req))))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))).build();
    }

    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository repository){OidcClientInitiatedLogoutSuccessHandler h=new OidcClientInitiatedLogoutSuccessHandler(repository);h.setPostLogoutRedirectUri("{baseUrl}/");return h;}
    private OidcUser loadOidcUserWithClientRoles(OidcUserService delegate,JwtDecoder decoder,OidcUserRequest req){OidcUser user=delegate.loadUser(req);Jwt token=decoder.decode(req.getAccessToken().getTokenValue());Set<GrantedAuthority> a=new LinkedHashSet<>(user.getAuthorities());a.addAll(roleConverter.convert(token));return new DefaultOidcUser(a,user.getIdToken(),user.getUserInfo());}
    private static void configureHeaders(HttpSecurity http)throws Exception{http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)).frameOptions(frame -> frame.deny()).referrerPolicy(ref -> ref.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)).permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()")).httpStrictTransportSecurity(h -> h.includeSubDomains(true).preload(true).maxAgeInSeconds(31536000)));}
    JwtAuthenticationConverter jwtAuthenticationConverter(){JwtAuthenticationConverter c=new JwtAuthenticationConverter();c.setJwtGrantedAuthoritiesConverter(roleConverter);return c;}

    static final class KeycloakRoleConverter implements Converter<Jwt,Collection<GrantedAuthority>> {
        private final String clientId,adminRole,operatorRole,viewerRole;
        KeycloakRoleConverter(String clientId,String adminRole,String operatorRole,String viewerRole){this.clientId=trim(clientId);this.adminRole=normalizeRole(adminRole);this.operatorRole=normalizeRole(operatorRole);this.viewerRole=normalizeRole(viewerRole);}
        @Override public Collection<GrantedAuthority> convert(Jwt jwt){Set<GrantedAuthority> a=new LinkedHashSet<>();Object rv=jwt.getClaims().get("resource_access");if(!(rv instanceof Map<?,?> resources))return a;Object cv=resources.get(clientId);if(!(cv instanceof Map<?,?> client))return a;Object rolesValue=client.get("roles");if(!(rolesValue instanceof Collection<?> roles))return a;for(Object value:roles){String role=normalizeRole(String.valueOf(value));if(role.equals(adminRole))a.add(new SimpleGrantedAuthority("ROLE_ADMIN"));else if(role.equals(operatorRole))a.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));else if(role.equals(viewerRole))a.add(new SimpleGrantedAuthority("ROLE_VIEWER"));}return a;}
        private static String trim(String v){return v==null?"":v.trim();} private static String normalizeRole(String v){return trim(v).toUpperCase();}
    }
}
