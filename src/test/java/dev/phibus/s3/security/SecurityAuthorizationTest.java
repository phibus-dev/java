package dev.phibus.s3.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.http.MediaType.TEXT_HTML;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import dev.phibus.s3.settings.ApplicationStateService;
import dev.phibus.s3.web.ApplicationVersionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityAuthorizationTest.ProbeController.class,
        properties = {"s3perf.security.enabled=true", "s3perf.security.audit-enabled=false"})
@Import({SecurityConfiguration.class, SecurityAuthorizationTest.ProbeController.class})
class SecurityAuthorizationTest {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private ApplicationStateService applicationStateService;

    @MockBean
    private ApplicationVersionProvider applicationVersionProvider;

    @BeforeEach
    void applicationIsReady() {
        when(applicationStateService.current()).thenReturn(ApplicationStateService.State.READY);
    }

    @Test
    void healthAndAgentProtocolRemainPublic() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());
        mvc.perform(post("/api/agents/register")).andExpect(status().isOk());
        mvc.perform(post("/api/agents/123/heartbeat")).andExpect(status().isOk());
    }

    @Test
    void settingsPageRequiresAuthenticationWhenSecurityIsEnabled() throws Exception {
        mvc.perform(get("/settings").accept(TEXT_HTML)).andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanReadButCannotMutateApi() throws Exception {
        mvc.perform(get("/api/read")).andExpect(status().isOk());
        mvc.perform(get("/settings")).andExpect(status().isForbidden());
        mvc.perform(post("/api/tests").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/history/123/rerun").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/run-history/123/rerun").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCanRunTestsButCannotChangeAdministration() throws Exception {
        mvc.perform(post("/api/tests").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/settings").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/config/import").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/agents/123/enabled").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void administratorCanChangeProtectedConfiguration() throws Exception {
        mvc.perform(get("/settings")).andExpect(status().isOk());
        mvc.perform(post("/api/settings").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/config/import").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/agents/123/enabled").with(csrf())).andExpect(status().isOk());
    }

    @RestController
    public static class ProbeController {
        @GetMapping({"/settings", "/api/read"})
        public String read() { return "ok"; }

        @PostMapping({"/api/agents/register", "/api/agents/{id}/heartbeat", "/api/agents/{id}/enabled",
                "/api/tests", "/api/history/{id}/rerun", "/api/run-history/{id}/rerun",
                "/api/settings", "/api/config/import"})
        public String write() { return "ok"; }
    }
}
